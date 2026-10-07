package com.youkeda.project.conversation;

import java.util.ArrayList;
import java.util.List;

public class ConversationRecord {

    private String conversationId;
    private List<ConversationMessage> messages = new ArrayList<>();
    private String summary = "";
    private int summarizedMessageCount;
    private long updatedAtMs;

    public ConversationRecord() {}

    public ConversationRecord(String conversationId) {
        this.conversationId = conversationId;
        this.updatedAtMs = System.currentTimeMillis();
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public List<ConversationMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public int getSummarizedMessageCount() {
        return summarizedMessageCount;
    }

    public void setSummarizedMessageCount(int summarizedMessageCount) {
        this.summarizedMessageCount = summarizedMessageCount;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public void setUpdatedAtMs(long updatedAtMs) {
        this.updatedAtMs = updatedAtMs;
    }
}
