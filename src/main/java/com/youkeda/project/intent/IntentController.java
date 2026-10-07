package com.youkeda.project.intent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intent")
public class IntentController {

    private final IntentClassifier intentClassifier;

    public IntentController(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    @PostMapping("/classify")
    public IntentResult classify(@RequestBody IntentRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        return intentClassifier.classify(request.text(), request.source());
    }
}
