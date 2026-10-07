package com.youkeda.project.wechat;

public record SendResult(String toUserId, String text, long sentAtMs, String message) {}
