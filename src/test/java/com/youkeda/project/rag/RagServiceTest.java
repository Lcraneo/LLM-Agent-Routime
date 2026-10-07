package com.youkeda.project.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagServiceTest {

    @TempDir
    Path tempDir;

    private RagService ragService;

    @BeforeEach
    void setUp() throws IOException {
        // 模拟知识库：退款政策 + 物流配送
        Files.writeString(
                tempDir.resolve("refund.txt"),
                "退款政策\n\n商品签收后 7 天内可申请退款。\n退货商品需保持全新未使用状态。\n退款在收到退货后 3-5 个工作日原路退回。");
        Files.writeString(
                tempDir.resolve("shipping.txt"),
                "物流配送\n\n下单后 24 小时内发货。\n一般商品 2-5 个工作日送达。");
        // 关键词检索 + 退款/物流/会员 触发词
        ragService = new RagService(tempDir.toString(), 3, 800, "退款,退货,物流,配送,会员");
    }

    @Test
    void loadsKnowledgeBaseIntoChunks() {
        List<RagChunk> chunks = ragService.allChunks();
        // 两个文件各按空行切成 2 块（标题 + 正文）
        assertEquals(4, chunks.size());
    }

    @Test
    void retrievesRefundChunkForRefundQuery() {
        List<RagChunk> result = ragService.retrieve("退款流程怎么走");
        assertFalse(result.isEmpty());
        assertTrue(result.get(0).score() > 0);
        assertTrue(result.stream().anyMatch(c -> c.content().contains("退款")));
    }

    @Test
    void domainKeywordTriggersRelevantKnowledge() {
        assertTrue(ragService.hasRelevantKnowledge("我想退款"));
        assertTrue(ragService.hasRelevantKnowledge("物流到哪了"));
        // 与知识库无关的查询不触发
        assertFalse(ragService.hasRelevantKnowledge("今天好无聊啊"));
    }

    @Test
    void buildEnhancedPromptContainsKnowledge() {
        List<RagChunk> chunks = ragService.retrieve("退款");
        String prompt = ragService.buildEnhancedPrompt("退款几天到账", chunks);
        assertTrue(prompt.contains("知识库"));
        assertTrue(prompt.contains("退款"));
        assertTrue(prompt.contains("用户问题"));
        assertTrue(prompt.contains("退款几天到账"));
    }

    @Test
    void emptyQueryReturnsEmpty() {
        assertTrue(ragService.retrieve("").isEmpty());
        assertTrue(ragService.retrieve("   ").isEmpty());
    }
}
