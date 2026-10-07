package com.youkeda.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void registersToolsAndBuildsOpenAiFunctionDefinitions() {
        AgentTool weather = stubTool("query_weather", "Query weather");
        ToolRegistry registry = new ToolRegistry(List.of(weather));

        assertEquals(List.of("query_weather"), registry.names());
        assertEquals(1, registry.definitions().size());
        ToolFunction definition = registry.definitions().get(0);
        assertEquals("function", definition.type());
        assertEquals("query_weather", definition.function().name());
        assertEquals("object", definition.function().parameters().get("type"));
        assertTrue(definition.function().parameters().containsKey("properties"));
    }

    @Test
    void executesRegisteredToolAndReportsUnknownTool() {
        AgentTool weather =
            new AgentTool() {
                @Override
                public String name() {
                    return "query_weather";
                }

                @Override
                public String description() {
                    return "Query weather";
                }

                @Override
                public Map<String, Object> parameters() {
                    return Map.of("type", "object", "properties", Map.of());
                }

                @Override
                public AgentToolResult execute(String argumentsJson) {
                    return AgentToolResult.text("beijing 晴 28℃");
                }
            };
        ToolRegistry registry = new ToolRegistry(List.of(weather));

        AgentToolResult result = registry.execute("query_weather", "{}");
        assertEquals("beijing 晴 28℃", result.content());
        assertEquals("工具不存在：unknown", registry.execute("unknown", "{}").content());
    }

    private AgentTool stubTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of());
            }

            @Override
            public AgentToolResult execute(String argumentsJson) {
                return AgentToolResult.text(description);
            }
        };
    }
}
