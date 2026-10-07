package com.youkeda.project.router;

import com.youkeda.project.rag.RagChunk;
import com.youkeda.project.rag.RagService;
import com.youkeda.project.skill.SkillRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 完整消息路由（极简关键词版）：
 *
 * <pre>
 * 用户消息
 *   → 命中 Skill 关键词？→ Skill 执行 → 回复
 *   → 命中 RAG 关键词？→ 增强 Prompt → LLM 回复
 *   → 都没命中？       → 直接 LLM 闲聊回复
 * </pre>
 *
 * <p>顺序很重要：Skill 是确定性的本地执行，优先级最高、延迟最低；
 * RAG 次之，需要先召回知识再交给 LLM；兜底交给 LLM 闲聊。
 */
@Service
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    public static final String TYPE_SKILL = "SKILL";
    public static final String TYPE_RAG = "RAG";
    public static final String TYPE_LLM = "LLM";

    private final SkillRegistry skillRegistry;
    private final RagService ragService;
    private final ChatGateway chatGateway;

    public MessageRouter(SkillRegistry skillRegistry, RagService ragService, ChatGateway chatGateway) {
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
        this.chatGateway = chatGateway;
    }

    /**
     * 路由一条用户消息。
     *
     * @param message    用户原始消息
     * @param ragEnabled 是否开启 RAG 分支（用于对比测试：true 走 RAG，false 跳过直接 LLM）
     */
    public RouteResult route(String message, boolean ragEnabled) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        String trimmed = message.trim();

        // 1. Skill 关键词命中 → 本地执行
        SkillRegistry.SkillMatch match = skillRegistry.match(trimmed);
        if (match != null) {
            String reply = match.skill().execute(trimmed);
            log.info("Route SKILL: skill={}, keyword={}", match.skill().name(), match.keyword());
            return new RouteResult(
                    reply, TYPE_SKILL, match.skill().name(), match.keyword(),
                    List.of(), null, ragEnabled);
        }

        // 2. RAG 关键词命中（且开启 RAG）→ 检索 + 增强 Prompt → LLM
        if (ragEnabled && ragService.hasRelevantKnowledge(trimmed)) {
            List<RagChunk> chunks = ragService.retrieve(trimmed);
            String enhanced = ragService.buildEnhancedPrompt(trimmed, chunks);
            String reply = chatGateway.chat(enhanced);
            log.info("Route RAG: chunks={}, ragEnabled={}", chunks.size(), ragEnabled);
            return new RouteResult(
                    reply, TYPE_RAG, null, null, chunks, enhanced, ragEnabled);
        }

        // 3. 都没命中 → LLM 兜底闲聊
        String reply = chatGateway.chat(trimmed);
        log.info("Route LLM: ragEnabled={}", ragEnabled);
        return new RouteResult(reply, TYPE_LLM, null, null, List.of(), null, ragEnabled);
    }
}
