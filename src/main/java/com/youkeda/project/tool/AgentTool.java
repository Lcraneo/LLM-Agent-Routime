package com.youkeda.project.tool;

import java.util.Map;

public interface AgentTool {

    String name();

    String description();

    Map<String, Object> parameters();

    AgentToolResult execute(String argumentsJson);

    default ToolFunction toolFunction() {
        return new ToolFunction(
            "function", new ToolFunctionDefinition(name(), description(), parameters()));
    }
}
