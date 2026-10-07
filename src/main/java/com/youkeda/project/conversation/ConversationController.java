package com.youkeda.project.conversation;

import com.youkeda.project.llm.QwenChatService;
import com.youkeda.project.search.SearchService;
import java.util.Base64;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    private final QwenChatService qwenChatService;
    private final SearchService searchService;
    private final ConversationMemoryService memoryService;

    public ConversationController(
        QwenChatService qwenChatService,
        SearchService searchService,
        ConversationMemoryService memoryService) {
        this.qwenChatService = qwenChatService;
        this.searchService = searchService;
        this.memoryService = memoryService;
    }

    @PostMapping("/chat")
    public ConversationChatResponse chat(@RequestBody ConversationChatRequest request) {
        String sessionId = normalizeSessionId(request.sessionId());
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isBlank() && (request.imageBase64() == null || request.imageBase64().isBlank())) {
            throw new IllegalArgumentException("message or imageBase64 is required");
        }

        ConversationContext context = memoryService.buildContext(sessionId);
        String reply;
        long now = System.currentTimeMillis();
        if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
            byte[] imageBytes = Base64.getDecoder().decode(request.imageBase64().trim());
            memoryService.append(
                sessionId,
                new ConversationMessage(
                    "user",
                    message.isBlank() ? "[图片]" : message,
                    "image",
                    now,
                    null));
            reply =
                qwenChatService.chatWithImage(
                    imageBytes, buildPrompt(context.formattedContext(), message));
        } else {
            String searchResults = searchService.search(message);
            memoryService.append(
                sessionId,
                new ConversationMessage("user", message, "text", now, null));
            reply =
                qwenChatService.chatWithSearch(
                    buildPrompt(context.formattedContext(), message), searchResults);
        }

        memoryService.append(
            sessionId,
            new ConversationMessage(
                "assistant", reply, "text", System.currentTimeMillis(), null));
        return new ConversationChatResponse(
            reply,
            sessionId,
            context.totalMessages() + 2,
            context.windowSize(),
            context.summary());
    }

    @GetMapping("/history")
    public List<ConversationMessage> history(
        @RequestParam(defaultValue = "default") String sessionId) {
        return memoryService.getMessages(normalizeSessionId(sessionId));
    }

    @DeleteMapping("/history")
    public void clear(@RequestParam(defaultValue = "default") String sessionId) {
        memoryService.clear(normalizeSessionId(sessionId));
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    private String buildPrompt(String context, String userText) {
        if (context == null || context.isBlank()) {
            return userText;
        }
        return "以下是之前的对话上下文：\n" + context + "\n\n当前用户输入：\n" + userText;
    }
}
