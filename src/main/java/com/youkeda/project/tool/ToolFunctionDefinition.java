package com.youkeda.project.tool;

import java.util.Map;

public record ToolFunctionDefinition(
    String name, String description, Map<String, Object> parameters) {}
