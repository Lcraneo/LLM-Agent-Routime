package com.youkeda.project.conversation;

import java.util.List;

public record ConversationContext(
    String conversationId,
    String summary,
    List<ConversationMessage> recentMessages,
    String formattedContext,
    int windowSize,
    int totalMessages,
    long updatedAtMs) {}
