package com.youkeda.project.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReplyDispatcherTest {

    @Test
    void preservesOrderWithinConversation() throws Exception {
        ReplyDispatcher dispatcher = new ReplyDispatcher(1, 16);
        try {
            List<String> order = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            for (int i = 1; i <= 3; i++) {
                int taskId = i;
                dispatcher.enqueue(
                    "user-1",
                    () -> {
                        order.add("task-" + taskId);
                        done.countDown();
                    });
            }

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("task-1", "task-2", "task-3"), order);
        } finally {
            dispatcher.close();
        }
    }

    @Test
    void tracksPendingTasksPerConversation() throws Exception {
        ReplyDispatcher dispatcher = new ReplyDispatcher(1, 16);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            dispatcher.enqueue(
                "user-1",
                () -> {
                    started.countDown();
                    try {
                        release.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(1, dispatcher.pending("user-1"));

            dispatcher.enqueue("user-1", () -> {});
            assertEquals(2, dispatcher.pending("user-1"));
            release.countDown();

            Thread.sleep(150);
            assertEquals(0, dispatcher.pending("user-1"));
        } finally {
            release.countDown();
            dispatcher.close();
        }
    }
}
