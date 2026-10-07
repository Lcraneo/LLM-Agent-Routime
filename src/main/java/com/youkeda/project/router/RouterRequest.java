package com.youkeda.project.router;

/**
 * 消息路由请求体：消息内容 + 是否开启 RAG。
 */
public record RouterRequest(String message, Boolean ragEnabled) {

    public boolean ragEnabledOrDefault() {
        return ragEnabled != null && ragEnabled;
    }
}
