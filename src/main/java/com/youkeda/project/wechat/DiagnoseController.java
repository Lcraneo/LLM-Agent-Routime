package com.youkeda.project.wechat;

import com.youkeda.project.intent.IntentClassifier;
import com.youkeda.project.intent.IntentResult;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/diagnose")
public class DiagnoseController {

    private final WeChatService weChatService;
    private final IntentClassifier intentClassifier;

    public DiagnoseController(WeChatService weChatService, IntentClassifier intentClassifier) {
        this.weChatService = weChatService;
        this.intentClassifier = intentClassifier;
    }

    @GetMapping("/voice")
    public Map<String, Object> voice() throws ReflectiveOperationException {
        Map<String, Object> result = new LinkedHashMap<>();
        Field field = WeChatService.class.getDeclaredField("voiceReplyEnabled");
        field.setAccessible(true);
        result.put("voiceReplyEnabled (runtime)", field.get(weChatService));
        String[] samples = {
            "用语音回复我", "语音回复", "用语音告诉我明天天气", "用语音回复说你好", "你好"
        };
        Map<String, String> intents = new LinkedHashMap<>();
        for (String text : samples) {
            IntentResult intent = intentClassifier.classify(text, "text");
            intents.put(text, intent.intent() + " (confidence=" + intent.confidence() + ")");
        }
        result.put("intent classification", intents);
        return result;
    }
}
