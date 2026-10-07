package com.youkeda.project.wechat;

public record ReceivedMessage(
    long receivedAtMs,
    Long messageId,
    String fromUserId,
    String toUserId,
    String type,
    String text,
    String description) {}
