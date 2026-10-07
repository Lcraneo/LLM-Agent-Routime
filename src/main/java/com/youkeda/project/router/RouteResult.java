package com.youkeda.project.router;

import com.youkeda.project.rag.RagChunk;
import java.util.List;

/**
 * 消息路由结果：回复内容 + 命中的分支类型 + 调试信息。
 *
 * <p>routeType 取值：
 * <ul>
 *   <li>{@code SKILL}：命中技能关键词，技能直接执行</li>
 *   <li>{@code RAG}：命中 RAG 关键词，检索知识库增强 Prompt 后交 LLM</li>
 *   <li>{@code LLM}：都没命中，LLM 兜底闲聊</li>
 * </ul>
 */
public record RouteResult(
        String reply,
        String routeType,
        String skillName,
        String matchedKeyword,
        List<RagChunk> ragChunks,
        String enhancedPrompt,
        boolean ragEnabled) {}
