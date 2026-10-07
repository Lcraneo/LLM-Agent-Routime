package com.youkeda.project.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.llm.QwenChatService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final Pattern IMAGE_INTENT_PATTERN =
        Pattern.compile(
            "^(?:生成图片|生成图像|生成一张图片|生成一张|生成一幅|帮我画|请画|给我画|画个|画张|画幅|画一|画一个|画一只|画一条|画一头|画座|画间|绘制|作画|draw|create image|generate image|generate)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern IMAGE_PROMPT_PATTERN =
        Pattern.compile(
            "^(?:生成图片|生成图像|生成一张图片|生成一张|生成一幅|帮我画|请画|给我画|画个|画张|画幅|画一|画一个|画一只|画一条|画一头|画座|画间|绘制|作画|draw|create image|generate image|generate)[:：,\\s]*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final List<String> VOICE_SIGNALS =
        List.of(
            "语音回复",
            "用语音",
            "发语音",
            "语音回答",
            "语音播报",
            "语音输出",
            "语音说",
            "朗读",
            "读出来",
            "说给我听",
            "播放出来",
            "语音方式回复",
            "voice reply",
            "reply by voice",
            "speak the answer",
            "say it aloud");

    @Value("${intent.llm-enabled:false}")
    private boolean llmEnabled;

    private final QwenChatService qwenChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentClassifier(QwenChatService qwenChatService) {
        this.qwenChatService = qwenChatService;
    }

    public IntentResult classify(String text) {
        return classify(text, "text");
    }

    public IntentResult classify(String text, String source) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        IntentResult ruleResult = ruleClassify(text);
        if (!llmEnabled || ruleResult.confidence() >= 0.9 || qwenChatService == null) {
            return ruleResult;
        }
        try {
            return llmClassify(text, source);
        } catch (RuntimeException e) {
            log.warn("LLM intent classification failed, using rule result: {}", e.getMessage());
            return ruleResult;
        }
    }

    IntentResult ruleClassify(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        boolean imageIntent = IMAGE_INTENT_PATTERN.matcher(normalized).find();
        boolean voiceIntent = false;
        for (String signal : VOICE_SIGNALS) {
            if (normalized.contains(signal)) {
                voiceIntent = true;
                break;
            }
        }

        List<String> signals = new ArrayList<>();
        if (imageIntent) {
            signals.add("image-action");
        }
        if (voiceIntent) {
            signals.add("voice-action");
        }
        if (imageIntent && voiceIntent) {
            return new IntentResult(IntentType.VOICE_REPLY, 0.95, signals, "rule");
        }
        if (imageIntent) {
            return new IntentResult(IntentType.IMAGE_GENERATION, 0.98, signals, "rule");
        }
        if (voiceIntent) {
            return new IntentResult(IntentType.VOICE_REPLY, 0.96, signals, "rule");
        }
        return new IntentResult(IntentType.TEXT_REPLY, 0.55, List.of("fallback"), "rule");
    }

    public String extractImagePrompt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = IMAGE_PROMPT_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        String prompt = matcher.group(1).trim();
        if (prompt.isBlank()) {
            return null;
        }
        String cleaned = prompt.replaceFirst("^[个张幅只条座位头]+", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private IntentResult llmClassify(String text, String source) {
        String prompt =
            "You are a strict intent classifier. Classify the user input into exactly one of: "
                + "TEXT_REPLY (answer in text), VOICE_REPLY (user explicitly asks for a voice/audio reply), "
                + "IMAGE_GENERATION (user asks to draw/generate an image). "
                + "Return only JSON like {\"intent\":\"VOICE_REPLY\",\"confidence\":0.99}. "
                + "Input source: "
                + (source == null || source.isBlank() ? "text" : source.trim())
                + ". User input: "
                + text.trim();
        String raw = qwenChatService.chat(prompt);
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(raw));
        } catch (IOException e) {
            throw new IllegalArgumentException("LLM returned invalid JSON", e);
        }
        String intentName = root.path("intent").asText("");
        double confidence = root.path("confidence").asDouble(0.9);
        IntentType intent;
        try {
            intent = IntentType.valueOf(intentName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            intent = IntentType.TEXT_REPLY;
        }
        return new IntentResult(intent, confidence, List.of("llm"), "llm");
    }

    private String extractJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM did not return JSON");
        }
        return trimmed.substring(start, end + 1);
    }
}
