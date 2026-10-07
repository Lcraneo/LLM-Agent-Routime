package com.youkeda.project.router;

import com.youkeda.project.rag.RagChunk;
import com.youkeda.project.rag.RagService;
import com.youkeda.project.skill.Skill;
import com.youkeda.project.skill.SkillRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息路由调试入口：用于验证 Skill / RAG / LLM 全流程。
 *
 * <ul>
 *   <li>{@code POST /api/router/message} 路由一条消息，返回分支类型与回复</li>
 *   <li>{@code GET  /api/router/skills} 列出已注册技能</li>
 *   <li>{@code GET  /api/router/rag} 列出已加载知识块</li>
 *   <li>{@code POST /api/router/rag/retrieve} 按查询召回知识块</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/router")
public class RouterController {

    private final MessageRouter messageRouter;
    private final SkillRegistry skillRegistry;
    private final RagService ragService;

    public RouterController(MessageRouter messageRouter, SkillRegistry skillRegistry, RagService ragService) {
        this.messageRouter = messageRouter;
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
    }

    /** 路由一条用户消息，ragEnabled 控制是否走 RAG 分支（便于对比测试）。 */
    @PostMapping("/message")
    public RouteResult route(@RequestBody RouterRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        return messageRouter.route(request.message(), request.ragEnabledOrDefault());
    }

    /** 列出已注册技能及其关键词。 */
    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        return skillRegistry.skills().stream()
                .map(skill -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", skill.name());
                    info.put("description", skill.description());
                    info.put("keywords", skill.keywords());
                    return info;
                })
                .toList();
    }

    /** 列出已加载的知识库块（含来源）。 */
    @GetMapping("/rag")
    public List<RagChunk> knowledge() {
        return ragService.allChunks();
    }

    /** 按查询关键词召回知识块，查看 RAG 检索效果。 */
    @PostMapping("/rag/retrieve")
    public List<RagChunk> retrieve(@RequestBody Map<String, String> body) {
        String query = body == null ? null : body.get("query");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        return ragService.retrieve(query);
    }
}
