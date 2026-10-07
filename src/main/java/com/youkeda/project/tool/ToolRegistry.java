package com.youkeda.project.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        if (toolList != null) {
            for (AgentTool tool : toolList) {
                tools.put(tool.name(), tool);
            }
        }
    }

    public List<ToolFunction> definitions() {
        return tools.values().stream().map(AgentTool::toolFunction).toList();
    }

    public AgentToolResult execute(String name, String argumentsJson) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return AgentToolResult.text("工具不存在：" + name);
        }
        try {
            return tool.execute(argumentsJson);
        } catch (RuntimeException e) {
            return AgentToolResult.text("工具执行失败：" + e.getMessage());
        }
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }
}
