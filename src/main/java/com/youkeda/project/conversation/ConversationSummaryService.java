package com.youkeda.project.conversation;

import com.youkeda.project.llm.QwenChatService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    @Value("${conversation.summary.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Value("${conversation.summary.max-chars:800}")
    private int maxChars = 800;

    private final QwenChatService qwenChatService;

    public ConversationSummaryService(QwenChatService qwenChatService) {
        this.qwenChatService = qwenChatService;
    }

    public String summarize(List<ConversationMessage> messages, String previousSummary) {
        if (messages == null || messages.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        if (llmEnabled && qwenChatService != null) {
            try {
                return truncate(qwenChatService.chat(buildLlmPrompt(messages, previousSummary)), maxChars);
            } catch (RuntimeException e) {
                log.warn("LLM conversation summary failed, using local summary: {}", e.getMessage());
            }
        }
        return truncate(summarizeLocally(messages, previousSummary), maxChars);
    }

    private String buildLlmPrompt(List<ConversationMessage> messages, String previousSummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对以下早期对话做简洁的中文摘要，保留关键事实、用户意图、偏好和未完成事项。");
        if (previousSummary != null && !previousSummary.isBlank()) {
            prompt.append("\n之前的摘要：").append(previousSummary);
        }
        prompt.append("\n=== 早期对话 ===\n");
        for (ConversationMessage message : messages) {
            prompt.append(message.role()).append(": ").append(message.content()).append("\n");
        }
        return prompt.toString();
    }

    private String summarizeLocally(List<ConversationMessage> messages, String previousSummary) {
        List<String> userMessages = new ArrayList<>();
        List<String> assistantMessages = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if ("user".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                userMessages.add(message.content().trim());
            } else if ("assistant".equals(message.role())
                && message.content() != null
                && !message.content().isBlank()) {
                assistantMessages.add(message.content().trim());
            }
        }

        StringBuilder summary = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            summary.append("此前摘要：").append(previousSummary).append("；");
        }
        summary.append("早期对话共").append(messages.size()).append("条");
        if (!userMessages.isEmpty()) {
            int limit = Math.min(3, userMessages.size());
            summary.append("，用户曾询问：").append(String.join("；", userMessages.subList(userMessages.size() - limit, userMessages.size())));
        }
        if (!assistantMessages.isEmpty()) {
            summary.append("；助手关键回答：").append(assistantMessages.get(assistantMessages.size() - 1));
        }
        return summary.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }
}
