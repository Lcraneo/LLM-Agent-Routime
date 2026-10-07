package com.youkeda.project.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsFullHistoryAndBuildsWindowWithSummary() {
        ConversationMemoryService service = newService();
        for (int i = 1; i <= 12; i++) {
            service.append("session-1", message("user", "问题" + i));
            service.append("session-1", message("assistant", "回答" + i));
        }

        ConversationContext context = service.buildContext("session-1");

        assertEquals(24, context.totalMessages());
        assertEquals(10, context.windowSize());
        assertEquals(10, context.recentMessages().size());
        assertEquals(24, service.getMessages("session-1").size());
        assertTrue(context.summary().contains("问题5") || context.summary().contains("问题7"));
        assertTrue(context.formattedContext().contains("[最近对话]"));
        assertTrue(context.formattedContext().contains("问题12"));
    }

    @Test
    void persistsConversationAcrossServiceRestarts() {
        ConversationMemoryService first = newService();
        first.append("session-persist", message("user", "我叫小明"));
        first.append("session-persist", message("assistant", "你好小明"));
        first.append("session-persist", message("user", "我想去上海"));

        ConversationMemoryService second = newService();
        List<ConversationMessage> history = second.getMessages("session-persist");

        assertEquals(3, history.size());
        assertEquals("我叫小明", history.get(0).content());
        assertEquals("你好小明", history.get(1).content());
        assertEquals("我想去上海", history.get(2).content());
    }

    @Test
    void buildsStableContextForLargeHistory() {
        ConversationMemoryService service = newService();
        for (int i = 1; i <= 100; i++) {
            service.append("session-large", message("user", "问题" + i));
            service.append("session-large", message("assistant", "回答" + i));
        }

        long start = System.nanoTime();
        ConversationContext context = service.buildContext("session-large");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(200, context.totalMessages());
        assertEquals(10, context.recentMessages().size());
        assertTrue(context.summary().contains("问题93") || context.summary().contains("问题95"));
        assertTrue(context.formattedContext().contains("问题100"));
        assertTrue(elapsedMs < 5000, "context build took too long: " + elapsedMs + "ms");
    }

    @Test
    void clearRemovesPersistedHistory() {
        ConversationMemoryService service = newService();
        service.append("session-clear", message("user", "hello"));
        service.clear("session-clear");

        assertEquals(0, service.getMessages("session-clear").size());
    }

    private ConversationMemoryService newService() {
        return new ConversationMemoryService(
            new ConversationSummaryService(null), tempDir.toString(), 10, 6, true, 800);
    }

    private ConversationMessage message(String role, String content) {
        return new ConversationMessage(role, content, "text", System.currentTimeMillis(), null);
    }
}
