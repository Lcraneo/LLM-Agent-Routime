// 文件路径: com/youkeda/project/tool/ToolExecutionRequest.java
package com.youkeda.project.tool;

public record ToolExecutionRequest(
        String id,          // 对应 tool_call_id
        String name,        // 工具名称
        String arguments    // JSON 参数字符串
) {}