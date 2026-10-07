package com.youkeda.project.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.youkeda.project.conversation.ConversationContext;
import com.youkeda.project.conversation.ConversationMemoryService;
import com.youkeda.project.conversation.ConversationMessage;
import com.youkeda.project.intent.IntentClassifier;
import com.youkeda.project.intent.IntentResult;
import com.youkeda.project.intent.IntentType;
import com.youkeda.project.llm.ImageGenerationRequest;
import com.youkeda.project.llm.ImageGenerationResponse;
import com.youkeda.project.llm.QwenChatService;
import com.youkeda.project.llm.WanxiangImageService;
import com.youkeda.project.search.SearchService;
import com.youkeda.project.tool.ToolChatResult;
import com.youkeda.project.voice.SilkEncoderService;
import com.youkeda.project.voice.SynthesizedAudio;
import com.youkeda.project.voice.VoiceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WeChatService {

    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);

    @Value("${wechat.ilink.connect-timeout-ms:35000}")
    private long connectTimeoutMs = 35000L;

    @Value("${wechat.ilink.read-timeout-ms:35000}")
    private long readTimeoutMs = 35000L;

    @Value("${wechat.ilink.write-timeout-ms:35000}")
    private long writeTimeoutMs = 35000L;

    @Value("${wechat.ilink.heartbeat-enabled:true}")
    private boolean heartbeatEnabled = true;

    @Value("${wechat.ilink.heartbeat-interval-ms:30000}")
    private long heartbeatIntervalMs = 30000L;

    @Value("${wechat.ilink.auto-reply-enabled:true}")
    private boolean autoReplyEnabled = true;

    @Value("${wechat.ilink.recent-message-limit:200}")
    private int recentMessageLimit = 200;

    @Value("${wechat.ilink.realtime-typing-enabled:true}")
    private boolean realtimeTypingEnabled = true;

    @Value("${wechat.ilink.typing-cooldown-ms:1200}")
    private long typingCooldownMs = 1200L;

    @Value("${wechat.ilink.search-before-reply:false}")
    private boolean searchBeforeReply = false;

    @Value("${wechat.ilink.voice-reply-enabled:true}")
    private boolean voiceReplyEnabled = true;

    @Value("${wechat.ilink.image-generation-threads:2}")
    private int imageGenerationThreads = 2;

    @Value("${wechat.ilink.login-threads:4}")
    private int loginThreads = 4;

    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    /** bot 的 botId(@im.bot) -> sessionId，用于从消息的 toUserId 反查会话（异步 flush 场景）。 */
    private final Map<String, String> botIdToSessionId = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTypingAt = new ConcurrentHashMap<>();

    private final QwenChatService qwenChatService;
    private final SearchService searchService;
    private final WanxiangImageService wanxiangImageService;
    private final VoiceService voiceService;
    private final SilkEncoderService silkEncoderService;
    private final IntentClassifier intentClassifier;
    private final MediaMessageMerger mediaMessageMerger;
    private final ConversationMemoryService conversationMemoryService;
    private final ReplyDispatcher replyDispatcher;
    private final UserTokenStore tokenStore;

    private ILinkConfig ilinkConfig;
    private ExecutorService loginExecutor;
    private ExecutorService imageGenerationExecutor;

    public WeChatService(
        QwenChatService qwenChatService,
        SearchService searchService,
        WanxiangImageService wanxiangImageService,
        VoiceService voiceService,
        SilkEncoderService silkEncoderService,
        IntentClassifier intentClassifier,
        MediaMessageMerger mediaMessageMerger,
        ConversationMemoryService conversationMemoryService,
        ReplyDispatcher replyDispatcher,
        UserTokenStore tokenStore) {
        this.qwenChatService = qwenChatService;
        this.searchService = searchService;
        this.wanxiangImageService = wanxiangImageService;
        this.voiceService = voiceService;
        this.silkEncoderService = silkEncoderService;
        this.intentClassifier = intentClassifier;
        this.mediaMessageMerger = mediaMessageMerger;
        this.conversationMemoryService = conversationMemoryService;
        this.replyDispatcher = replyDispatcher;
        this.tokenStore = tokenStore;
        // 异步 flush 到达时，消息里只有 toUserId（即 bot 的 botId），靠反查表定位会话。
        this.mediaMessageMerger.setFlushHandler(
            merged -> {
                String sid = sessionByBotId(merged.toUserId());
                if (sid != null) {
                    submitReply(sid, merged);
                }
            });
    }

    @PostConstruct
    public void init() {
        this.ilinkConfig =
            ILinkConfig.builder()
                .connectTimeoutMs(connectTimeoutMs)
                .readTimeoutMs(readTimeoutMs)
                .writeTimeoutMs(writeTimeoutMs)
                .heartbeatEnabled(heartbeatEnabled)
                .heartbeatIntervalMs(heartbeatIntervalMs)
                .build();

        this.imageGenerationExecutor =
            Executors.newFixedThreadPool(
                Math.max(1, imageGenerationThreads),
                runnable -> {
                    Thread thread = new Thread(runnable, "image-generation");
                    thread.setDaemon(true);
                    return thread;
                });

        // 扫码登录的 HTTP 请求（executeLogin -> getQRCode）放在独立线程池，
        // 多个用户同时扫码登录时不会相互阻塞，也不会占满 Tomcat 请求线程。
        int coreLogin = Math.max(1, loginThreads);
        this.loginExecutor =
            new ThreadPoolExecutor(
                coreLogin,
                coreLogin,
                60L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, coreLogin * 8)),
                runnable -> {
                    Thread thread = new Thread(runnable, "wechat-login");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        // 启动时恢复所有已保存 token 的会话，用户无需重新扫码。
        resumeAll();
        log.info("WeChat iLink service initialized, sessions={}", sessions.size());
    }

    /** 从磁盘读出所有 token，用 resumeContext 重建每个会话的 client。 */
    private void resumeAll() {
        Map<String, LoginContext> saved = tokenStore.loadAll();
        saved.forEach(
            (sid, loginContext) -> {
                try {
                    ILinkClient client = buildClient(sid, ResumeContext.of(loginContext));
                    UserSession session = new UserSession(sid, client);
                    session.setAutoReplyEnabled(autoReplyEnabled);
                    sessions.put(sid, session);
                    botIdToSessionId.put(loginContext.getBotId(), sid);
                    log.info(
                        "Resumed session {} for botId={}", sid, loginContext.getBotId());
                } catch (RuntimeException e) {
                    log.warn("Resume session {} failed: {}", sid, e.getMessage());
                }
            });
    }

    /**
     * 为某个 session 构造 ILinkClient 并装配监听器。
     *
     * @param sessionId 会话标识
     * @param resume   非空时用已保存的 token 直接恢复登录态；为 null 表示走扫码流程。
     */
    private ILinkClient buildClient(String sessionId, ResumeContext resume) {
        ILinkClientBuilder builder = ILinkClient.builder().config(ilinkConfig);
        if (resume != null) {
            builder.resumeContext(resume);
        }
        return builder
            .onLogin(
                new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        // 扫码成功后保存 token，下次重启可直接恢复；同时登记反查表。
                        tokenStore.save(sessionId, context);
                        if (context.getBotId() != null) {
                            botIdToSessionId.put(context.getBotId(), sessionId);
                        }
                        log.info(
                            "WeChat login success, session={}, botId={}, userId={}",
                            sessionId,
                            context.getBotId(),
                            context.getUserId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.warn(
                            "WeChat login failed, session={}: {}",
                            sessionId,
                            throwable == null ? "unknown" : throwable.getMessage());
                    }
                })
            .onMessage(
                new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        handleMessages(sessionId, messages);
                    }
                })
            .onDisconnect(
                new OnDisconnectListener() {
                    @Override
                    public void onDisconnect(Throwable cause) {
                        log.warn(
                            "WeChat disconnected, session={}: {}",
                            sessionId,
                            cause == null ? "unknown" : cause.getMessage());
                    }

                    @Override
                    public void onReconnectStart(int attempt) {
                        log.info(
                            "WeChat reconnect starting, session={}, attempt={}",
                            sessionId,
                            attempt);
                    }

                    @Override
                    public void onReconnectSuccess() {
                        log.info("WeChat reconnect success, session={}", sessionId);
                    }

                    @Override
                    public void onReconnectFailed(Throwable cause) {
                        log.warn(
                            "WeChat reconnect failed, session={}: {}",
                            sessionId,
                            cause == null ? "unknown" : cause.getMessage());
                    }
                })
            .onHeartbeat(
                new OnHeartbeatListener() {
                    @Override
                    public void onHeartbeatSuccess() {
                        log.debug("WeChat heartbeat success, session={}", sessionId);
                    }

                    @Override
                    public void onHeartbeatFailure(Throwable cause) {
                        log.warn(
                            "WeChat heartbeat failed, session={}: {}",
                            sessionId,
                            cause == null ? "unknown" : cause.getMessage());
                    }
                })
            .build();
    }

    @PreDestroy
    public void close() {
        if (loginExecutor != null) {
            loginExecutor.shutdownNow();
        }
        if (imageGenerationExecutor != null) {
            imageGenerationExecutor.shutdownNow();
        }
        sessions.values().forEach(s -> s.getClient().close());
        log.info("WeChat iLink service closed, sessions={}", sessions.size());
    }

    /** 列出所有会话及其登录状态，供前端展示。 */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        sessions.forEach(
            (sid, session) -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sessionId", sid);
                info.put("loggedIn", session.getClient().isLoggedIn());
                info.put(
                    "connectionStatus",
                    session.getClient().getConnectionStatus() == null
                        ? null
                        : session.getClient().getConnectionStatus().name());
                info.put("autoReply", session.isAutoReplyEnabled());
                info.put("receivedMessageCount", session.getReceivedMessages().size());
                result.add(info);
            });
        return result;
    }

    /**
     * 为指定 session 启动扫码登录。
     *
     * <p>executeLogin 内部会发起 HTTP 申请二维码，这里把它丢进 loginExecutor，
     * 多用户并发扫码时各自跑在独立线程，互不阻塞。立即返回状态，前端轮询获取二维码。
     */
    public WeChatStatus startLogin(String sessionId) {
        // 用 computeIfAbsent 拿到（或新建）会话，保证 session 是 effectively final，
        // 才能被下面的 loginExecutor lambda 引用。
        UserSession session =
            sessions.computeIfAbsent(
                sessionId,
                id -> {
                    UserSession s = new UserSession(id, buildClient(id, null));
                    s.setAutoReplyEnabled(autoReplyEnabled);
                    return s;
                });
        ILinkClient c = session.getClient();
        if (c.isLoggedIn()) {
            return currentStatus(sessionId, "Already logged in");
        }
        session.setQrCode(null);
        session.setQrCodeContent(null);
        loginExecutor.execute(
            () -> {
                try {
                    session.setQrCodeContent(c.executeLogin());
                    session.setQrCode(c.getQrcode());
                } catch (RuntimeException e) {
                    log.warn("Start login for session {} failed: {}", sessionId, e.getMessage());
                }
            });
        return currentStatus(sessionId, "Login started, scan the QR code");
    }

    public byte[] renderQrCodePng(String sessionId) {
        UserSession session = session(sessionId);
        String content = session.getQrCodeContent();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("QR code is not ready, call login first");
        }
        try {
            BitMatrix matrix =
                new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 360, 360);
            BufferedImage image =
                new BufferedImage(
                    matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new WeChatOperationException("No PNG writer available", null);
            }
            return output.toByteArray();
        } catch (WriterException | IOException e) {
            throw new WeChatOperationException(
                "Generate WeChat QR code failed: " + e.getMessage(), e);
        }
    }

    public WeChatStatus currentStatus(String sessionId) {
        return currentStatus(sessionId, null);
    }

    public WeChatStatus setAutoReply(String sessionId, boolean enabled) {
        UserSession s = sessions.get(sessionId);
        if (s != null) {
            s.setAutoReplyEnabled(enabled);
        }
        return currentStatus(sessionId, "Auto reply " + (enabled ? "enabled" : "disabled"));
    }

    public SendResult sendText(String sessionId, String toUserId, String text) {
        if (toUserId == null || toUserId.isBlank()) {
            throw new IllegalArgumentException("toUserId is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        try {
            requireClient(sessionId).sendText(toUserId.trim(), text);
            return new SendResult(toUserId.trim(), text, System.currentTimeMillis(), "sent");
        } catch (IOException e) {
            throw new WeChatOperationException(
                "Send WeChat text failed: " + e.getMessage(), e);
        }
    }

    public SendResult sendVoiceReply(String sessionId, String toUserId, String text) {
        return sendVoiceReply(sessionId, toUserId, text, "mp3");
    }

    /**
     * 用语音回复指定用户。
     *
     * <p>微信 iLink bot 协议当前不支持发送原生语音气泡（服务端接受但客户端不渲染，
     * 参见 hermes-weixin-voice-bubble、cc-connect#763 等项目的实测结论）。
     * 因此采用业界通行的降级方案：TTS 合成 MP3，以文件消息发送，用户点击即可播放。
     */
    public SendResult sendVoiceReply(String sessionId, String toUserId, String text, String format) {
        if (toUserId == null || toUserId.isBlank()) {
            throw new IllegalArgumentException("toUserId is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        SynthesizedAudio audio = voiceService.synthesize(text, "mp3");
        try {
            requireClient(sessionId)
                .sendFile(
                    toUserId.trim(),
                    audio.bytes(),
                    "语音回复.mp3",
                    "[语音回复] 点击文件即可收听");
            log.info(
                "Voice reply (MP3 file) sent to {}: mp3Bytes={}, sampleRate={}",
                toUserId,
                audio.bytes().length,
                audio.sampleRate());
            return new SendResult(
                toUserId.trim(), text, System.currentTimeMillis(), "voice sent");
        } catch (IOException e) {
            throw new WeChatOperationException(
                "Send WeChat voice failed: " + e.getMessage(), e);
        }
    }

    public List<ReceivedMessage> pollMessages(String sessionId) {
        UserSession s = sessions.get(sessionId);
        if (s == null) {
            return List.of();
        }
        try {
            s.getClient().getUpdates();
        } catch (IOException e) {
            throw new WeChatOperationException(
                "Poll WeChat messages failed: " + e.getMessage(), e);
        }
        return snapshotMessages(sessionId);
    }

    public List<ReceivedMessage> snapshotMessages(String sessionId) {
        UserSession s = sessions.get(sessionId);
        return s == null ? List.of() : List.copyOf(s.getReceivedMessages());
    }

    private void handleMessages(String sessionId, List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        UserSession session = session(sessionId);
        List<IncomingMessage> incoming = new ArrayList<>();
        for (WeixinMessage msg : messages) {
            ReceivedMessage received = toReceivedMessage(msg);
            if (received.fromUserId() != null && received.fromUserId().endsWith("@im.bot")) {
                continue;
            }
            incoming.add(new IncomingMessage(received, msg));
            log.info(
                "Received WeChat message from={}, type={}, text={}",
                received.fromUserId(),
                received.type(),
                received.text());
        }

        for (IncomingMessage incomingMessage : incoming) {
            session.getReceivedMessages().add(incomingMessage.received());
        }
        session.trim(recentMessageLimit);

        if (!session.isAutoReplyEnabled()) {
            return;
        }
        notifyReceived(sessionId, incoming);
        for (MergedIncoming merged : mediaMessageMerger.merge(incoming)) {
            submitReply(sessionId, merged);
        }
    }

    private void submitReply(String sessionId, MergedIncoming merged) {
        UserSession session = session(sessionId);
        if (!session.isAutoReplyEnabled()
            || merged == null
            || merged.parts() == null
            || merged.parts().isEmpty()
            || merged.fromUserId() == null
            || merged.fromUserId().endsWith("@im.bot")) {
            return;
        }
        String key = conversationId(merged.fromUserId(), merged.toUserId());
        int pending = replyDispatcher.enqueue(key, () -> processReplySafely(sessionId, merged));
        if (pending > 1 && realtimeTypingEnabled) {
            startTyping(sessionId, merged.fromUserId());
        }
    }

    private void processReplySafely(String sessionId, MergedIncoming merged) {
        if (merged == null || merged.fromUserId() == null) {
            return;
        }
        try {
            startTyping(sessionId, merged.fromUserId());
            replyToMerged(sessionId, merged);
        } catch (RuntimeException e) {
            log.warn(
                "Queued reply to {} failed: {}",
                merged.fromUserId(),
                e.getMessage(),
                e);
        } finally {
            stopTyping(sessionId, merged.fromUserId());
        }
    }

    private void notifyReceived(String sessionId, List<IncomingMessage> incoming) {
        UserSession session = session(sessionId);
        if (!realtimeTypingEnabled
            || !session.isAutoReplyEnabled()
            || incoming == null
            || incoming.isEmpty()) {
            return;
        }
        Map<String, List<ReceivedMessage>> groups = new LinkedHashMap<>();
        for (IncomingMessage incomingMessage : incoming) {
            ReceivedMessage received = incomingMessage.received();
            if (received == null
                || received.fromUserId() == null
                || received.fromUserId().endsWith("@im.bot")) {
                continue;
            }
            groups.computeIfAbsent(
                    conversationId(received.fromUserId(), received.toUserId()),
                    key -> new ArrayList<>())
                .add(received);
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<ReceivedMessage>> entry : groups.entrySet()) {
            Long lastTypingAtMs = lastTypingAt.get(entry.getKey());
            if (lastTypingAtMs != null && now - lastTypingAtMs < typingCooldownMs) {
                continue;
            }
            lastTypingAt.put(entry.getKey(), now);
            startTyping(sessionId, entry.getValue().get(0).fromUserId());
        }
    }

    private void startTyping(String sessionId, String toUserId) {
        if (!realtimeTypingEnabled || toUserId == null || toUserId.isBlank()) {
            return;
        }
        try {
            requireClient(sessionId).startTyping(toUserId.trim());
        } catch (IOException e) {
            log.debug("Start typing for {} failed: {}", toUserId, e.getMessage());
        }
    }

    private void stopTyping(String sessionId, String toUserId) {
        if (!realtimeTypingEnabled || toUserId == null || toUserId.isBlank()) {
            return;
        }
        try {
            requireClient(sessionId).stopTyping(toUserId.trim());
        } catch (IOException e) {
            log.debug("Stop typing for {} failed: {}", toUserId, e.getMessage());
        }
    }

    private void replyToMerged(String sessionId, MergedIncoming merged) {
        UserSession session = session(sessionId);
        if (!session.isAutoReplyEnabled()
            || merged == null
            || merged.parts() == null
            || merged.parts().isEmpty()) {
            return;
        }
        if (merged.fromUserId() == null || merged.fromUserId().endsWith("@im.bot")) {
            return;
        }

        recordMergedMessage(sessionId, merged);
        try {
            if (!merged.isMergedImageText()) {
                replyTo(sessionId, merged.parts().get(0));
                return;
            }

            String conversationId = conversationId(merged.fromUserId(), merged.toUserId());
            ConversationContext context = conversationMemoryService.buildContext(conversationId);
            byte[] imageBytes = downloadImage(sessionId, merged.firstImage().raw());
            String userText = merged.fusedText();
            if (userText == null || userText.isBlank()) {
                userText = "请分析这张图片";
            }
            IntentResult intent = intentClassifier.classify(userText, "text");
            appendUserMessage(conversationId, userText, "merged", null);
            if (intent.intent() == IntentType.IMAGE_GENERATION) {
                String imageDescription =
                    qwenChatService.chatWithImage(
                        imageBytes,
                        buildContextPrompt(
                            context,
                            "请用中文简要描述这张图片的主要内容，用于后续生成类似图片。"));
                String requestedPrompt = intentClassifier.extractImagePrompt(userText);
                String generationPrompt =
                    MediaContentFusion.buildGenerationPrompt(
                        requestedPrompt == null ? userText : requestedPrompt,
                        imageDescription);
                dispatchImageGeneration(
                    sessionId,
                    merged.fromUserId(),
                    conversationId,
                    context,
                    userText,
                    generationPrompt);
                return;
            }

            String reply =
                qwenChatService.chatWithImage(
                    imageBytes, buildContextPrompt(context, userText));
            sendMergedReply(sessionId, merged.fromUserId(), reply, intent.intent());
            appendAssistantMessage(conversationId, reply);
        } catch (IOException | RuntimeException e) {
            log.warn(
                "Merged image+text reply to {} failed: {}",
                merged.fromUserId(),
                e.getMessage());
        }
    }

    private void recordMergedMessage(String sessionId, MergedIncoming merged) {
        if (!merged.isMergedImageText()) {
            return;
        }
        UserSession session = session(sessionId);
        session.getReceivedMessages()
            .add(
                new ReceivedMessage(
                    System.currentTimeMillis(),
                    null,
                    merged.fromUserId(),
                    merged.toUserId(),
                    "merged",
                    merged.fusedText(),
                    "merged image+text"));
        session.trim(recentMessageLimit);
    }

    private void sendMergedReply(
        String sessionId, String toUserId, String reply, IntentType intent)
        throws IOException {
        if (reply == null || reply.isBlank()) {
            return;
        }
        if (voiceReplyEnabled && intent == IntentType.VOICE_REPLY) {
            sendVoiceReply(sessionId, toUserId, reply);
        } else {
            requireClient(sessionId).sendText(toUserId, reply);
        }
    }

    private void replyTo(String sessionId, IncomingMessage incoming) {
        ReceivedMessage received = incoming.received();
        try {
            String conversationId = conversationId(received.fromUserId(), received.toUserId());
            ConversationContext context = conversationMemoryService.buildContext(conversationId);
            if ("image".equals(received.type())) {
                byte[] imageBytes = downloadImage(sessionId, incoming.raw());
                appendUserMessage(
                    conversationId, "[图片]", "image", received.messageId());
                String reply =
                    qwenChatService.chatWithImage(
                        imageBytes, buildContextPrompt(context, "请分析这张图片"));
                if (reply != null && !reply.isBlank()) {
                    requireClient(sessionId).sendText(received.fromUserId(), reply);
                }
                appendAssistantMessage(conversationId, reply);
                return;
            }

            String userText = resolveUserText(sessionId, incoming);
            if (userText == null || userText.isBlank()) {
                sendTextSafe(sessionId, received.fromUserId(), "没能识别你的消息，请重新发送文字或语音。");
                return;
            }
            String source = "voice".equals(received.type()) ? "speech" : "text";
            IntentResult intent = intentClassifier.classify(userText, source);
            appendUserMessage(conversationId, userText, received.type(), received.messageId());
            if (intent.intent() == IntentType.IMAGE_GENERATION) {
                String generationPrompt = intentClassifier.extractImagePrompt(userText);
                dispatchImageGeneration(
                    sessionId,
                    received.fromUserId(),
                    conversationId,
                    context,
                    userText,
                    generationPrompt == null ? userText : generationPrompt);
                return;
            }

            String searchResults = searchBeforeReply ? searchService.search(userText) : "";
            String toolPrompt = buildContextPrompt(context, userText);
            if (searchResults != null && !searchResults.isBlank()) {
                toolPrompt =
                    "请参考以下实时搜索结果，如果搜索结果中没有相关信息，请明确告知用户。\n"
                        + "=== 搜索结果 ===\n"
                        + searchResults.trim()
                        + "\n\n"
                        + toolPrompt;
            }
            ToolChatResult toolResult = qwenChatService.chatWithTools(toolPrompt);
            String reply = toolResult == null ? null : toolResult.content();
            if (reply != null && !reply.isBlank()) {
                boolean userSentVoice = "voice".equals(received.type());
                boolean preferVoice =
                    voiceReplyEnabled
                        && (intent.intent() == IntentType.VOICE_REPLY || userSentVoice);
                log.info(
                    "Reply decision for {}: msgType={}, intent={}, voiceReplyEnabled={}, "
                        + "userSentVoice={}, preferVoice={}",
                    received.fromUserId(),
                    received.type(),
                    intent.intent(),
                    voiceReplyEnabled,
                    userSentVoice,
                    preferVoice);
                if (preferVoice) {
                    try {
                        sendVoiceReply(sessionId, received.fromUserId(), reply);
                    } catch (RuntimeException e) {
                        log.warn(
                            "Voice reply to {} failed, falling back to text: {}",
                            received.fromUserId(),
                            e.getMessage());
                        requireClient(sessionId).sendText(received.fromUserId(), reply);
                    }
                } else {
                    requireClient(sessionId).sendText(received.fromUserId(), reply);
                    if (toolResult != null
                        && toolResult.imageUrl() != null
                        && !toolResult.imageUrl().isBlank()) {
                        sendGeneratedImageUrl(
                            sessionId,
                            received.fromUserId(),
                            conversationId,
                            toolResult.imageUrl(),
                            null);
                    }
                }
            }
            appendAssistantMessage(conversationId, reply);
        } catch (IOException | RuntimeException e) {
            log.warn(
                "LLM reply to {} failed: {}",
                received.fromUserId(),
                e.getMessage());
            // 降级回复：LLM 配额用完 / 超时 / 网络异常时，至少告诉用户一声，
            // 并把错误码带出来（比如 HTTP 403 = 配额耗尽，用户自己就能快速判断）
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String hint;
            if (msg.contains("403") || msg.contains("Free quota") || msg.contains("Quota")) {
                hint = "AI 服务配额已用完（HTTP 403），请联系管理员更换 API Key 或开通付费额度。";
            } else if (msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out")) {
                hint = "AI 服务响应超时，请稍后再试。";
            } else if (msg.contains("not configured") || msg.contains("API_KEY")) {
                hint = "AI 服务未配置 API Key，请联系管理员。";
            } else {
                hint = "AI 服务暂时不可用，请稍后重试；如持续失败，请联系管理员。";
            }
            try {
                requireClient(sessionId).sendText(received.fromUserId(), hint);
            } catch (IOException | RuntimeException ignore) {
                log.warn("Degraded reply also failed for {}", received.fromUserId());
            }
        }
    }

    private String resolveUserText(String sessionId, IncomingMessage incoming) throws IOException {
        ReceivedMessage received = incoming.received();
        if (received.text() != null && !received.text().isBlank()) {
            return received.text().trim();
        }
        if ("voice".equals(received.type())) {
            try {
                byte[] voiceBytes = downloadVoice(sessionId, incoming.raw());
                String transcript = voiceService.transcribe(voiceBytes, null, null);
                if (transcript != null && !transcript.isBlank()) {
                    return transcript.trim();
                }
            } catch (IOException | RuntimeException e) {
                log.warn(
                    "Voice transcription failed for {}: {}",
                    received.fromUserId(),
                    e.getMessage());
            }
            return null;
        }
        return received.text();
    }

    private void sendTextSafe(String sessionId, String toUserId, String text) {
        if (toUserId == null || toUserId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        try {
            requireClient(sessionId).sendText(toUserId.trim(), text);
        } catch (IOException e) {
            log.warn("Send fallback text to {} failed: {}", toUserId, e.getMessage());
        }
    }

    private String buildContextPrompt(ConversationContext context, String userText) {
        String history = context == null ? "" : context.formattedContext();
        if (history == null || history.isBlank()) {
            return userText;
        }
        return "以下是之前的对话上下文：\n" + history + "\n\n当前用户输入：\n" + userText;
    }

    private void appendUserMessage(
        String conversationId, String content, String type, Long messageId) {
        conversationMemoryService.append(
            conversationId,
            new ConversationMessage(
                "user", content, type, System.currentTimeMillis(), messageId));
    }

    private void appendAssistantMessage(String conversationId, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        conversationMemoryService.append(
            conversationId,
            new ConversationMessage(
                "assistant", content, "text", System.currentTimeMillis(), null));
    }

    private String conversationId(String fromUserId, String toUserId) {
        String from = fromUserId == null ? "unknown" : fromUserId;
        String to = toUserId == null ? "unknown" : toUserId;
        return "wechat:" + from + ":" + to;
    }

    private void dispatchImageGeneration(
        String sessionId,
        String toUserId,
        String conversationId,
        ConversationContext context,
        String rawText,
        String prompt) {
        log.info(
            "Image generation requested by {}: raw=[{}], prompt=[{}]",
            toUserId,
            rawText,
            prompt);
        startTyping(sessionId, toUserId);
        imageGenerationExecutor.execute(
            () -> {
                try {
                    startTyping(sessionId, toUserId);
                    ToolChatResult result =
                        qwenChatService.chatWithTools(
                            buildContextPrompt(context, prompt));
                    if (result != null
                        && result.imageUrl() != null
                        && !result.imageUrl().isBlank()) {
                        sendGeneratedImageUrl(
                            sessionId,
                            toUserId,
                            conversationId,
                            result.imageUrl(),
                            result.content());
                    } else {
                        log.warn(
                            "Model did not call generate_image for {} prompt=[{}], "
                                + "falling back to direct image generation",
                            toUserId,
                            prompt);
                        sendGeneratedImage(sessionId, toUserId, conversationId, prompt);
                    }
                } catch (IOException | RuntimeException e) {
                    log.error(
                        "Image generation for {} (prompt=[{}]) failed: {}",
                        toUserId,
                        prompt,
                        e.getMessage(),
                        e);
                    appendAssistantMessage(
                        conversationId, "图片生成失败：" + e.getMessage());
                    try {
                        requireClient(sessionId)
                            .sendText(
                                toUserId,
                                "图片生成失败，请稍后重试或换个描述试试。错误信息："
                                    + e.getMessage());
                    } catch (IOException sendError) {
                        log.warn(
                            "Send failure notice to {} failed: {}",
                            toUserId,
                            sendError.getMessage());
                    }
                } finally {
                    stopTyping(sessionId, toUserId);
                }
            });
    }

    private void sendGeneratedImage(
        String sessionId, String toUserId, String conversationId, String prompt)
        throws IOException {
        long startAt = System.currentTimeMillis();
        ImageGenerationResponse generated =
            wanxiangImageService.generate(
                new ImageGenerationRequest(prompt, null, null, 1, null, null, null));
        if (generated.imageUrls() == null || generated.imageUrls().isEmpty()) {
            throw new IllegalStateException("Image generation returned no image URL");
        }
        String imageUrl = generated.imageUrls().get(0);
        sendGeneratedImageUrl(sessionId, toUserId, conversationId, imageUrl, null);
    }

    private void sendGeneratedImageUrl(
        String sessionId,
        String toUserId,
        String conversationId,
        String imageUrl,
        String assistantText)
        throws IOException {
        long startAt = System.currentTimeMillis();
        log.info(
            "Image generated for {} in {} ms: {}",
            toUserId,
            System.currentTimeMillis() - startAt,
            imageUrl);
        try {
            byte[] imageBytes = wanxiangImageService.downloadImage(imageUrl);
            requireClient(sessionId).sendImage(toUserId, imageBytes, "generated.png", null);
            log.info("Generated image sent to {} as media", toUserId);
            if (assistantText != null && !assistantText.isBlank()) {
                requireClient(sessionId).sendText(toUserId, assistantText);
            }
        } catch (IOException | RuntimeException e) {
            log.warn(
                "Send generated image as media failed, sending URL instead: {}",
                e.getMessage());
            requireClient(sessionId).sendText(toUserId, "Generated image: " + imageUrl);
        }
        appendAssistantMessage(
            conversationId,
            assistantText == null || assistantText.isBlank()
                ? "已生成图片：" + imageUrl
                : assistantText);
    }

    private byte[] downloadImage(String sessionId, WeixinMessage msg) throws IOException {
        if (msg.getItem_list() != null) {
            for (MessageItem item : msg.getItem_list()) {
                if (item != null && item.getImage_item() != null) {
                    return requireClient(sessionId).downloadImageFromMessageItem(item);
                }
            }
        }
        throw new IllegalStateException("Message has no image media");
    }

    private byte[] downloadVoice(String sessionId, WeixinMessage msg) throws IOException {
        if (msg.getItem_list() != null) {
            for (MessageItem item : msg.getItem_list()) {
                if (item != null && item.getVoice_item() != null) {
                    return requireClient(sessionId).downloadVoiceFromMessageItem(item);
                }
            }
        }
        throw new IllegalStateException("Message has no voice media");
    }

    private ReceivedMessage toReceivedMessage(WeixinMessage msg) {
        String type = "unknown";
        String text = "";
        StringBuilder description = new StringBuilder();

        List<MessageItem> items = msg.getItem_list();
        if (items != null) {
            for (MessageItem item : items) {
                if (item == null) {
                    continue;
                }
                if (item.getText_item() != null && item.getText_item().getText() != null) {
                    type = "text";
                    text = item.getText_item().getText();
                    description.append(text);
                } else if (item.getImage_item() != null) {
                    type = "image";
                    description.append("image");
                } else if (item.getFile_item() != null) {
                    type = "file";
                    description.append(
                        item.getFile_item().getFile_name() == null
                            ? "file"
                            : item.getFile_item().getFile_name());
                } else if (item.getVoice_item() != null) {
                    type = "voice";
                    log.info(
                        "Inbound WeChat voice metadata: encode_type={}, sample_rate={}, "
                            + "bits_per_sample={}, playtime={}, hasMedia={}",
                        item.getVoice_item().getEncode_type(),
                        item.getVoice_item().getSample_rate(),
                        item.getVoice_item().getBits_per_sample(),
                        item.getVoice_item().getPlaytime(),
                        item.getVoice_item().getMedia() != null);
                    String transcript = item.getVoice_item().getText();
                    if (transcript != null && !transcript.isBlank()) {
                        text = transcript;
                        description.append(transcript);
                    } else {
                        description.append("voice");
                    }
                } else if (item.getVideo_item() != null) {
                    type = "video";
                    description.append("video");
                }
            }
        }

        return new ReceivedMessage(
            System.currentTimeMillis(),
            msg.getMessage_id(),
            msg.getFrom_user_id(),
            msg.getTo_user_id(),
            type,
            text,
            description.toString());
    }

    private WeChatStatus currentStatus(String sessionId, String message) {
        // 页面打开时可能尚未登录，会话还不存在；返回一个空闲状态，不抛异常。
        UserSession session = sessions.get(sessionId);
        if (session == null) {
            return new WeChatStatus(
                false,
                null,
                null,
                null,
                null,
                null,
                autoReplyEnabled,
                0,
                replyDispatcher.totalPending(),
                message == null ? "尚未登录，请点击扫码登录" : message);
        }
        ILinkClient c = session.getClient();
        LoginContext loginContext = c.getLoginContext();
        LoginStatus loginStatus = c.getLoginStatus();
        boolean staleQr =
            loginStatus == null
                || loginStatus.getStatus() == LoginStatus.Status.EXPIRED
                || loginStatus.getStatus() == LoginStatus.Status.ERROR;
        return new WeChatStatus(
            c.isLoggedIn(),
            loginContext == null ? null : loginContext.getBotId(),
            c.getConnectionStatus() == null ? null : c.getConnectionStatus().name(),
            loginStatus == null ? null : loginStatus.getStatus().name(),
            staleQr ? null : session.getQrCode(),
            staleQr ? null : session.getQrCodeContent(),
            session.isAutoReplyEnabled(),
            session.getReceivedMessages().size(),
            replyDispatcher.totalPending(),
            message);
    }

    private UserSession session(String sessionId) {
        UserSession s = sessions.get(sessionId);
        if (s == null) {
            throw new IllegalStateException("WeChat session not found: " + sessionId);
        }
        return s;
    }

    private ILinkClient requireClient(String sessionId) {
        return session(sessionId).getClient();
    }

    /** 用消息里的 toUserId（即 bot 的 botId）反查所属会话。 */
    private String sessionByBotId(String botId) {
        return botId == null ? null : botIdToSessionId.get(botId);
    }
}
