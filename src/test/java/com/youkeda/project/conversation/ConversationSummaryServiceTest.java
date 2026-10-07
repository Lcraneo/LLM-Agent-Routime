package com.youkeda.project.conversation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationSummaryServiceTest {

    @Test
    void localSummaryKeepsKeyEarlyFacts() {
        ConversationSummaryService summaryService = new ConversationSummaryService(null);
        List<ConversationMessage> messages =
            List.of(
                message("user", "我叫小明，我喜欢猫"),
                message("assistant", "好的小明，我记住了你喜欢猫"),
                message("user", "我下周要去上海出差"),
                message("assistant", "好的，祝你上海出差顺利"));

        String summary = summaryService.summarize(messages, "");

        assertTrue(summary.contains("小明"));
        assertTrue(summary.contains("猫"));
        assertTrue(summary.contains("上海"));
        assertTrue(summary.contains("4条"));
    }

    private ConversationMessage message(String role, String content) {
        return new ConversationMessage(role, content, "text", System.currentTimeMillis(), null);
    }
}
