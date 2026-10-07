package com.youkeda.project.tool;

public record AgentToolResult(String content, String imageUrl) {

    public static AgentToolResult text(String content) {
        return new AgentToolResult(content, null);
    }

    public static AgentToolResult image(String content, String imageUrl) {
        return new AgentToolResult(content, imageUrl);
    }
}
