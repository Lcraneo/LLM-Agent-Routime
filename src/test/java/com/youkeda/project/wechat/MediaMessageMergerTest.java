package com.youkeda.project.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaMessageMergerTest {

    private final List<MediaMessageMerger> mergers = new ArrayList<>();

    @AfterEach
    void shutdownMergers() {
        for (MediaMessageMerger merger : mergers) {
            merger.shutdown();
        }
        mergers.clear();
    }

    @Test
    void mergesImageThenTextWithinWindow() {
        MediaMessageMerger merger = newMerger(2000, 16);
        long now = System.currentTimeMillis();
        List<IncomingMessage> messages =
            List.of(
                image("user-1", "bot-1", 11L, now, "image"),
                text("user-1", "bot-1", 12L, now + 1, "看下这个是什么"));

        List<MergedIncoming> result = merger.merge(messages);

        assertEquals(1, result.size());
        MergedIncoming merged = result.get(0);
        assertTrue(merged.isMergedImageText());
        assertEquals("看下这个是什么", merged.fusedText());
        assertEquals(2, merged.parts().size());
        assertTrue(merged.associationId().startsWith("ilink-media://"));
    }

    @Test
    void mergesTextThenImageWithinWindow() {
        MediaMessageMerger merger = newMerger(2000, 16);
        long now = System.currentTimeMillis();
        List<IncomingMessage> messages =
            List.of(
                text("user-1", "bot-1", 21L, now, "这张图里有什么"),
                image("user-1", "bot-1", 22L, now + 1, "image"));

        List<MergedIncoming> result = merger.merge(messages);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isMergedImageText());
        assertEquals("这张图里有什么", result.get(0).fusedText());
    }

    @Test
    void doesNotMergeDifferentUsers() throws Exception {
        MediaMessageMerger merger = newMerger(60, 16);
        CountDownLatch flushed = new CountDownLatch(2);
        List<MergedIncoming> captured = new ArrayList<>();
        merger.setFlushHandler(
            merged -> {
                captured.add(merged);
                flushed.countDown();
            });
        long now = System.currentTimeMillis();
        List<IncomingMessage> messages =
            List.of(
                image("user-1", "bot-1", 31L, now, "image"),
                text("user-2", "bot-1", 32L, now + 1, "你好"));

        List<MergedIncoming> result = merger.merge(messages);

        assertTrue(result.isEmpty());
        assertTrue(flushed.await(2, TimeUnit.SECONDS));
        assertEquals(2, captured.size());
        assertFalse(captured.get(0).isMergedImageText());
        assertFalse(captured.get(1).isMergedImageText());
    }

    @Test
    void flushesSingleTextAfterWindowExpires() throws Exception {
        MediaMessageMerger merger = newMerger(80, 16);
        CountDownLatch flushed = new CountDownLatch(1);
        List<MergedIncoming> captured = new ArrayList<>();
        merger.setFlushHandler(
            merged -> {
                captured.add(merged);
                flushed.countDown();
            });

        long now = System.currentTimeMillis();
        merger.merge(List.of(text("user-1", "bot-1", 41L, now, "你好")));

        assertTrue(flushed.await(2, TimeUnit.SECONDS));
        assertEquals(1, captured.size());
        assertEquals("你好", captured.get(0).fusedText());
        assertFalse(captured.get(0).isMergedImageText());
    }

    @Test
    void flushesSingleTextAtFastWindowBeforeMergeWindow() throws Exception {
        MediaMessageMerger merger = new MediaMessageMerger(5000, 16, 80);
        mergers.add(merger);
        CountDownLatch flushed = new CountDownLatch(1);
        List<MergedIncoming> captured = new ArrayList<>();
        merger.setFlushHandler(
            merged -> {
                captured.add(merged);
                flushed.countDown();
            });

        long now = System.currentTimeMillis();
        merger.merge(List.of(text("user-1", "bot-1", 51L, now, "快一点回复")));

        assertTrue(flushed.await(1, TimeUnit.SECONDS));
        assertEquals(1, captured.size());
        assertEquals("快一点回复", captured.get(0).fusedText());
        assertFalse(captured.get(0).isMergedImageText());
    }

    private MediaMessageMerger newMerger(long windowMs, int maxBuffers) {
        MediaMessageMerger merger = new MediaMessageMerger(windowMs, maxBuffers);
        mergers.add(merger);
        return merger;
    }

    private IncomingMessage text(String from, String to, long messageId, long time, String text) {
        return new IncomingMessage(
            new ReceivedMessage(time, messageId, from, to, "text", text, text), null);
    }

    private IncomingMessage image(String from, String to, long messageId, long time, String description) {
        return new IncomingMessage(
            new ReceivedMessage(time, messageId, from, to, "image", "", description), null);
    }
}
