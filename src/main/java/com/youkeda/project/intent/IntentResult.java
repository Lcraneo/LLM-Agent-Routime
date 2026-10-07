package com.youkeda.project.intent;

import java.util.List;

public record IntentResult(
    IntentType intent,
    double confidence,
    List<String> matchedSignals,
    String method) {}
