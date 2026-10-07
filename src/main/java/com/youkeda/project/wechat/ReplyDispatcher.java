package com.youkeda.project.wechat;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReplyDispatcher {

    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> chains =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    public ReplyDispatcher(
        @Value("${wechat.ilink.reply-threads:8}") int threads,
        @Value("${wechat.ilink.reply-queue-capacity:512}") int queueCapacity) {
        int coreThreads = Math.max(1, threads);
        this.executor =
            new ThreadPoolExecutor(
                coreThreads,
                coreThreads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "wechat-reply");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public int enqueue(String key, Runnable task) {
        String normalizedKey = key == null || key.isBlank() ? "default" : key;
        AtomicInteger counter = pending.computeIfAbsent(normalizedKey, k -> new AtomicInteger());
        int current = counter.incrementAndGet();

        CompletableFuture<Void> next =
            chains.compute(
                normalizedKey,
                (k, tail) -> {
                    CompletableFuture<Void> base =
                        tail == null ? CompletableFuture.completedFuture(null) : tail;
                    return base.handle((unused, error) -> null)
                        .thenRunAsync(task, executor);
                });

        next.whenComplete(
            (unused, error) -> {
                int remaining = counter.decrementAndGet();
                if (remaining <= 0) {
                    pending.remove(normalizedKey, counter);
                    chains.remove(normalizedKey, next);
                }
            });
        return current;
    }

    public int pending(String key) {
        if (key == null) {
            return 0;
        }
        AtomicInteger counter = pending.get(key);
        return counter == null ? 0 : counter.get();
    }

    public int totalPending() {
        return pending.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
