package com.youkeda.project.wechat;

import com.github.wechat.ilink.sdk.ILinkClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 一个用户会话 = 一个绑定的微信 bot 账号。
 *
 * <p>每个会话拥有独立的 ILinkClient、二维码状态和消息缓冲，互不影响。
 * WeChatService 通过 sessionId 在 sessions 集合中查找对应的 UserSession。
 */
public class UserSession {

    private final String sessionId;
    private final ILinkClient client;
    private volatile String qrCode;
    private volatile String qrCodeContent;
    private volatile boolean autoReplyEnabled = true;
    private final CopyOnWriteArrayList<ReceivedMessage> receivedMessages =
        new CopyOnWriteArrayList<>();

    public UserSession(String sessionId, ILinkClient client) {
        this.sessionId = sessionId;
        this.client = client;
    }

    public boolean isAutoReplyEnabled() {
        return autoReplyEnabled;
    }

    public void setAutoReplyEnabled(boolean autoReplyEnabled) {
        this.autoReplyEnabled = autoReplyEnabled;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ILinkClient getClient() {
        return client;
    }

    public String getQrCode() {
        return qrCode;
    }

    public void setQrCode(String qrCode) {
        this.qrCode = qrCode;
    }

    public String getQrCodeContent() {
        return qrCodeContent;
    }

    public void setQrCodeContent(String qrCodeContent) {
        this.qrCodeContent = qrCodeContent;
    }

    public List<ReceivedMessage> getReceivedMessages() {
        return receivedMessages;
    }

    /** 保留最近 limit 条消息，超出则从头部丢弃。 */
    public void trim(int limit) {
        while (receivedMessages.size() > limit) {
            receivedMessages.remove(0);
        }
    }
}
