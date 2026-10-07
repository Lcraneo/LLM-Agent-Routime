package com.youkeda.project.tool;

import java.util.List;

public record ToolChatResponse(
    String reply,
    String imageUrl,
    List<String> calledTools,
    String sessionId,
    int totalMessages,
    int windowSize,
    String summary) {}
