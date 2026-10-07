package com.youkeda.project.router;

/**
 * LLM 聊天网关：把一个文本 prompt 交给大模型并返回回复。
 *
 * <p>引入这个接口而非直接依赖 {@code QwenChatService}，是为了让
 * {@link MessageRouter} 可在测试中注入假的 LLM，验证 RAG 增强 Prompt 的差异，
 * 而不必真实调用 DashScope。
 */
@FunctionalInterface
public interface ChatGateway {
    String chat(String prompt);
}
