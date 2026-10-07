package com.youkeda.project.tool;

public record ToolExecutionResult(
        String id,                  // 对应 tool_call_id
        String name,
        String content,             // 执行结果文本
        String imageUrl,            // 可选图片 URL
        Throwable error,            // 执行异常（若有）
        long durationMs             // 执行耗时
) {
    public boolean isSuccess() {
        return error == null;
    }
}