package com.youkeda.project.wechat;

public record WeChatStatus(
    boolean loggedIn,
    String botId,
    String connectionStatus,
    String loginStatus,
    String qrCode,
    String qrCodeContent,
    boolean autoReply,
    int receivedMessageCount,
    int pendingReplyCount,
    String message) {}
