package com.youkeda.project.conversation;

public record ConversationMessage(
    String role,
    String content,
    String type,
    long timestampMs,
    Long messageId) {}
