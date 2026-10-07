package com.youkeda.project.wechat;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaMessageMerger {

    private static final Logger log = LoggerFactory.getLogger(MediaMessageMerger.class);

    private final long windowMs;
    private final long fastMergeWindowMs;
    private final int maxBuffers;
    private final Map<String, MergeBuffer> buffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "media-merge-flush");
                thread.setDaemon(true);
                return thread;
            });

    private volatile Consumer<MergedIncoming> flushHandler;

    @Autowired
    public MediaMessageMerger(
        @Value("${wechat.ilink.media-merge-window-ms:5000}") long windowMs,
        @Value("${wechat.ilink.media-merge-max-buffers:256}") int maxBuffers,
        @Value("${wechat.ilink.media-fast-merge-window-ms:800}") long fastMergeWindowMs) {
        this.windowMs = Math.max(1, windowMs);
        this.fastMergeWindowMs = Math.max(1, Math.min(fastMergeWindowMs, this.windowMs));
        this.maxBuffers = Math.max(1, maxBuffers);
    }

    public MediaMessageMerger(long windowMs, int maxBuffers) {
        this(windowMs, maxBuffers, Math.min(800, Math.max(1, windowMs / 2)));
    }

    public void setFlushHandler(Consumer<MergedIncoming> flushHandler) {
        this.flushHandler = flushHandler;
    }

    public synchronized List<MergedIncoming> merge(List<IncomingMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<IncomingMessage> ordered =
            messages.stream()
                .sorted(
                    Comparator.comparingLong(
                            (IncomingMessage message) -> message.received().receivedAtMs())
                        .thenComparing(
                            message ->
                                message.received().messageId() == null
                                    ? Long.MAX_VALUE
                                    : message.received().messageId()))
                .toList();

        List<MergedIncoming> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        flushExpired(now, result);

        for (IncomingMessage message : ordered) {
            ReceivedMessage received = message.received();
            if (!isMergeable(received.type())) {
                result.add(toSingle(message, null));
                continue;
            }

            String key = associationKey(received);
            MergeBuffer buffer = buffers.get(key);
            if (buffer != null && now > buffer.flushAtMs()) {
                buffers.remove(key, buffer);
                result.add(buffer.toMerged());
                buffer = null;
            }
            if (buffer == null) {
                evictIfNeeded(result);
                buffer = new MergeBuffer(received, now, windowMs, fastMergeWindowMs);
                buffers.put(key, buffer);
                scheduleFlush(buffer);
            }

            buffer.add(message);
            if (buffer.isReady()) {
                buffers.remove(key, buffer);
                result.add(buffer.toMerged());
            }
        }
        return result;
    }

    private void flushExpired(long now, List<MergedIncoming> result) {
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, MergeBuffer> entry : buffers.entrySet()) {
            if (now > entry.getValue().flushAtMs()) {
                expiredKeys.add(entry.getKey());
            }
        }
        for (String key : expiredKeys) {
            MergeBuffer buffer = buffers.remove(key);
            if (buffer != null) {
                result.add(buffer.toMerged());
            }
        }
    }

    private void evictIfNeeded(List<MergedIncoming> result) {
        if (buffers.size() < maxBuffers) {
            return;
        }
        MergeBuffer oldest = null;
        for (MergeBuffer buffer : buffers.values()) {
            if (oldest == null || buffer.expiresAtMs < oldest.expiresAtMs) {
                oldest = buffer;
            }
        }
        if (oldest != null && buffers.remove(oldest.key, oldest)) {
            result.add(oldest.toMerged());
            log.warn("Media merge buffer evicted: {}", oldest.associationId);
        }
    }

    private void scheduleFlush(MergeBuffer buffer) {
        long delay = Math.max(0, buffer.flushAtMs() - System.currentTimeMillis());
        scheduler.schedule(
            () -> {
                synchronized (MediaMessageMerger.this) {
                    if (buffers.remove(buffer.key, buffer)) {
                        log.info(
                            "Media merge flush: associationId={}, parts={}",
                            buffer.associationId,
                            buffer.parts.size());
                        Consumer<MergedIncoming> handler = flushHandler;
                        if (handler != null) {
                            handler.accept(buffer.toMerged());
                        }
                    }
                }
            },
            delay,
            TimeUnit.MILLISECONDS);
    }

    private boolean isMergeable(String type) {
        return "text".equals(type) || "image".equals(type);
    }

    private MergedIncoming toSingle(IncomingMessage message, String associationId) {
        ReceivedMessage received = message.received();
        return new MergedIncoming(
            associationId,
            received.fromUserId(),
            received.toUserId(),
            received.text(),
            List.of(message));
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class MergeBuffer {
        private final String key;
        private final String associationId;
        private final String fromUserId;
        private final String toUserId;
        private final long createdAtMs;
        private final long expiresAtMs;
        private final long fastExpiresAtMs;
        private final List<IncomingMessage> parts = new ArrayList<>();
        private boolean hasText;
        private boolean hasImage;

        private MergeBuffer(
            ReceivedMessage first, long now, long windowMs, long fastMergeWindowMs) {
            this.key = associationKey(first);
            this.fromUserId = first.fromUserId();
            this.toUserId = first.toUserId();
            this.createdAtMs = now;
            this.expiresAtMs = now + windowMs;
            boolean fastTrack = "text".equals(first.type()) || "image".equals(first.type());
            this.fastExpiresAtMs =
                fastTrack
                    ? now + Math.min(fastMergeWindowMs, windowMs)
                    : this.expiresAtMs;
            this.associationId =
                "ilink-media://"
                    + safePart(fromUserId)
                    + "/"
                    + safePart(toUserId)
                    + "/"
                    + (first.messageId() == null ? "none" : first.messageId())
                    + "/"
                    + createdAtMs;
        }

        private long flushAtMs() {
            return Math.min(expiresAtMs, fastExpiresAtMs);
        }

        private void add(IncomingMessage message) {
            parts.add(message);
            String type = message.received().type();
            hasText |= "text".equals(type);
            hasImage |= "image".equals(type);
        }

        private boolean isReady() {
            return hasText && hasImage;
        }

        private MergedIncoming toMerged() {
            List<String> texts = new ArrayList<>();
            for (IncomingMessage part : parts) {
                texts.add(part.received().text());
            }
            return new MergedIncoming(
                associationId,
                fromUserId,
                toUserId,
                MediaContentFusion.fuseText(texts),
                List.copyOf(parts));
        }
    }

    private static String associationKey(ReceivedMessage received) {
        String from = received.fromUserId() == null ? "" : received.fromUserId();
        String to = received.toUserId() == null ? "" : received.toUserId();
        return from + "::" + to;
    }

    private static String safePart(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replace(':', '_');
    }
}
