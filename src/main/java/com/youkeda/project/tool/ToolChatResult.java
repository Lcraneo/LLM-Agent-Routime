package com.youkeda.project.tool;

import java.util.List;

public record ToolChatResult(
    String content, String imageUrl, List<String> calledTools) {}
