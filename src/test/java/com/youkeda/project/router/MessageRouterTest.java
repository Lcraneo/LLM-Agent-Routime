package com.youkeda.project.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.youkeda.project.rag.RagService;
import com.youkeda.project.skill.JokeSkill;
import com.youkeda.project.skill.SkillRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证完整消息路由全流程：Skill → RAG → LLM 兜底，以及 RAG 开/关对比。
 *
 * <p>使用一个假的 {@link ChatGateway} 捕获真正发给 LLM 的 prompt，
 * 这样无需真实 DashScope API Key 也能验证 RAG 是否增强了 Prompt。
 */
class MessageRouterTest {

    @TempDir
    Path tempDir;

    private MessageRouter router;
    private CapturingGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        // 知识库：退款政策
        Files.writeString(
                tempDir.resolve("refund.txt"),
                "退款政策\n\n商品签收后7天内可申请退款。\n退款在收到退货后3-5个工作日原路退回。");
        RagService rag = new RagService(tempDir.toString(), 3, 800, "退款,退货,会员,物流");
        SkillRegistry skills = new SkillRegistry(List.of(new JokeSkill()));
        gateway = new CapturingGateway();
        router = new MessageRouter(skills, rag, gateway);
    }

    /** 分支 1：命中 Skill 关键词 → 本地执行，不调用 LLM。 */
    @Test
    void skillBranchExecutesLocallyWithoutLlm() {
        RouteResult result = router.route("给我讲个笑话呗", true);

        assertEquals(MessageRouter.TYPE_SKILL, result.routeType());
        assertEquals("joke", result.skillName());
        // 关键词按注册顺序匹配，“讲个笑话”排在“笑话”前面，先命中
        assertEquals("讲个笑话", result.matchedKeyword());
        assertNotNull(result.reply());
        assertFalse(result.reply().isBlank());
        // 关键：技能分支根本没走 LLM
        assertTrue(gateway.prompts.isEmpty(),
                "Skill 分支不应调用 LLM，但捕获到 prompt: " + gateway.prompts);
    }

    /** 分支 2：命中 RAG 关键词且开启 RAG → 增强 Prompt 交给 LLM。 */
    @Test
    void ragBranchEnhancesPromptWithKnowledge() {
        String query = "我想退款几天能到账";

        RouteResult result = router.route(query, true);

        assertEquals(MessageRouter.TYPE_RAG, result.routeType());
        assertTrue(result.ragEnabled());
        assertFalse(result.ragChunks().isEmpty(), "RAG 分支应召回知识块");
        assertEquals(1, gateway.prompts.size());
        String llmPrompt = gateway.prompts.get(0);
        assertTrue(llmPrompt.contains("知识库"), "增强 Prompt 应包含知识库标识");
        assertTrue(llmPrompt.contains("退款"), "增强 Prompt 应包含召回的退款知识");
        assertEquals("LLM_REPLY", result.reply());
    }

    /** 分支 3：都没命中 → LLM 兜底闲聊，发送原始查询。 */
    @Test
    void llmFallbackBranchSendsRawQuery() {
        String query = "今天好无聊啊想找人聊天";

        RouteResult result = router.route(query, true);

        assertEquals(MessageRouter.TYPE_LLM, result.routeType());
        assertEquals(1, gateway.prompts.size());
        // 兜底分支直接把原始查询交给 LLM，无知识库增强
        assertEquals(query, gateway.prompts.get(0));
    }

    /**
     * RAG 开/关对比测试（任务4核心）：
     * 同一条领域查询，开启 RAG 时 LLM 收到的是 “知识库 + 问题” 的增强 Prompt，
     * 关闭 RAG 时 LLM 只收到原始查询。
     */
    @Test
    void ragOnVsOffComparisonProducesDifferentPrompts() {
        String query = "退款流程是什么";

        RouteResult on = router.route(query, true);
        RouteResult off = router.route(query, false);

        assertEquals(MessageRouter.TYPE_RAG, on.routeType(), "开启 RAG 应走 RAG 分支");
        assertEquals(MessageRouter.TYPE_LLM, off.routeType(), "关闭 RAG 应走 LLM 兜底分支");

        String onPrompt = gateway.prompts.get(0);
        String offPrompt = gateway.prompts.get(1);

        assertTrue(onPrompt.contains("知识库"), "RAG 开启时 Prompt 应含知识库");
        assertTrue(onPrompt.contains("退款"), "RAG 开启时 Prompt 应含召回知识");
        assertFalse(offPrompt.contains("知识库"), "RAG 关闭时 Prompt 不应含知识库");
        assertEquals(query, offPrompt, "RAG 关闭时应直接发送原始查询");
    }

    /** 捕获 LLM 调用 prompt 的假网关，固定返回 LLM_REPLY。 */
    static class CapturingGateway implements ChatGateway {
        final List<String> prompts = new ArrayList<>();

        @Override
        public String chat(String prompt) {
            prompts.add(prompt);
            return "LLM_REPLY";
        }
    }
}
