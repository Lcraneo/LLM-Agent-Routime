package com.youkeda.project.conversation;

public record ConversationChatRequest(
    String sessionId,
    String message,
    String imageBase64,
    String mimeType) {}
