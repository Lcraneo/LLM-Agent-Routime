package com.youkeda.project.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationSummaryService summaryService;
    private final Path storageDir;
    private final int windowSize;
    private final int summaryTrigger;
    private final boolean summaryEnabled;
    private final int summaryMaxChars;
    private final Map<String, ConversationRecord> cache = new ConcurrentHashMap<>();

    public ConversationMemoryService(
        ConversationSummaryService summaryService,
        @Value("${conversation.storage-dir:./data/conversations}") String storageDir,
        @Value("${conversation.window-size:10}") int windowSize,
        @Value("${conversation.summary-trigger:6}") int summaryTrigger,
        @Value("${conversation.summary-enabled:true}") boolean summaryEnabled,
        @Value("${conversation.summary-max-chars:800}") int summaryMaxChars) {
        this.summaryService = summaryService;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.windowSize = Math.max(2, windowSize);
        this.summaryTrigger = Math.max(1, summaryTrigger);
        this.summaryEnabled = summaryEnabled;
        this.summaryMaxChars = Math.max(100, summaryMaxChars);
    }

    public synchronized List<ConversationMessage> getMessages(String conversationId) {
        return List.copyOf(load(conversationId).getMessages());
    }

    public synchronized void append(String conversationId, ConversationMessage message) {
        if (conversationId == null || conversationId.isBlank() || message == null) {
            return;
        }
        ConversationRecord record = load(conversationId);
        record.getMessages().add(message);
        record.setUpdatedAtMs(System.currentTimeMillis());
        save(record);
    }

    public synchronized ConversationContext buildContext(String conversationId) {
        ConversationRecord record = load(conversationId);
        List<ConversationMessage> all = record.getMessages();
        int start = Math.max(0, all.size() - windowSize);
        List<ConversationMessage> oldMessages = new ArrayList<>(all.subList(0, start));
        List<ConversationMessage> recentMessages = new ArrayList<>(all.subList(start, all.size()));

        if (summaryEnabled
            && oldMessages.size() >= summaryTrigger
            && oldMessages.size() > record.getSummarizedMessageCount()) {
            String generated = summaryService.summarize(oldMessages, record.getSummary());
            if (generated != null && !generated.isBlank()) {
                record.setSummary(truncate(generated, summaryMaxChars));
                record.setSummarizedMessageCount(oldMessages.size());
                save(record);
            }
        }

        String formatted = formatContext(record.getSummary(), recentMessages);
        return new ConversationContext(
            conversationId,
            record.getSummary(),
            recentMessages,
            formatted,
            windowSize,
            all.size(),
            record.getUpdatedAtMs());
    }

    public synchronized void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        cache.remove(conversationId);
        try {
            Files.deleteIfExists(fileFor(conversationId));
        } catch (IOException e) {
            log.warn("Failed to delete conversation {}: {}", conversationId, e.getMessage());
        }
    }

    private ConversationRecord load(String conversationId) {
        return cache.computeIfAbsent(conversationId, this::readFromDisk);
    }

    private ConversationRecord readFromDisk(String conversationId) {
        Path file = fileFor(conversationId);
        if (Files.exists(file)) {
            try {
                ConversationRecord record =
                    objectMapper.readValue(file.toFile(), ConversationRecord.class);
                record.setConversationId(conversationId);
                return record;
            } catch (IOException e) {
                log.warn("Failed to read conversation {}: {}", conversationId, e.getMessage());
            }
        }
        return new ConversationRecord(conversationId);
    }

    private void save(ConversationRecord record) {
        try {
            Files.createDirectories(storageDir);
            Path file = fileFor(record.getConversationId());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), record);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to persist conversation " + record.getConversationId() + ": " + e.getMessage(),
                e);
        }
    }

    private Path fileFor(String conversationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return storageDir.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String formatContext(String summary, List<ConversationMessage> recentMessages) {
        StringBuilder context = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            context.append("[早期对话摘要]\n").append(summary).append("\n\n");
        }
        context.append("[最近对话]\n");
        for (ConversationMessage message : recentMessages) {
            String role = "user".equals(message.role()) ? "用户" : "助手";
            context.append(role).append(": ").append(message.content()).append("\n");
        }
        return context.toString().trim();
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
