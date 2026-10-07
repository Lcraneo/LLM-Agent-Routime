package com.youkeda.project.tool;

import com.youkeda.project.conversation.ConversationContext;
import com.youkeda.project.conversation.ConversationMemoryService;
import com.youkeda.project.conversation.ConversationMessage;
import com.youkeda.project.llm.QwenChatService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tool")
public class ToolController {

    private final QwenChatService qwenChatService;
    private final ToolRegistry toolRegistry;
    private final ConversationMemoryService memoryService;

    public ToolController(
        QwenChatService qwenChatService,
        ToolRegistry toolRegistry,
        ConversationMemoryService memoryService) {
        this.qwenChatService = qwenChatService;
        this.toolRegistry = toolRegistry;
        this.memoryService = memoryService;
    }

    @GetMapping("/list")
    public List<ToolFunction> list() {
        return toolRegistry.definitions();
    }

    @PostMapping("/chat")
    public ToolChatResponse chat(@RequestBody ToolChatRequest request) {
        String sessionId = normalizeSessionId(request.sessionId());
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }

        ConversationContext context = memoryService.buildContext(sessionId);
        memoryService.append(
            sessionId,
            new ConversationMessage(
                "user", message, "text", System.currentTimeMillis(), null));

        ToolChatResult result =
            qwenChatService.chatWithTools(buildPrompt(context.formattedContext(), message));
        String reply = result == null ? "" : result.content();
        memoryService.append(
            sessionId,
            new ConversationMessage(
                "assistant",
                reply == null ? "" : reply,
                "text",
                System.currentTimeMillis(),
                null));

        return new ToolChatResponse(
            reply,
            result == null ? null : result.imageUrl(),
            result == null ? List.of() : result.calledTools(),
            sessionId,
            context.totalMessages() + 2,
            context.windowSize(),
            context.summary());
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    private String buildPrompt(String context, String userText) {
        if (context == null || context.isBlank()) {
            return userText;
        }
        return "以下是之前的对话上下文：\n"
            + context
            + "\n\n当前用户输入：\n"
            + userText;
    }
}
