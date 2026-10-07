package com.youkeda.project.rag;

/**
 * 知识库检索单元：来源 + 内容 + 关键词命中得分。
 */
public record RagChunk(String source, String content, int score) {

    public static RagChunk of(String source, String content, int score) {
        return new RagChunk(source, content, score);
    }
}
