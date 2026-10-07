package com.youkeda.project.conversation;

public record ConversationChatResponse(
    String reply,
    String sessionId,
    int totalMessages,
    int windowSize,
    String summary) {}
