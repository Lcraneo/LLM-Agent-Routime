package com.youkeda.project.llm;

import com.youkeda.project.tool.AgentToolResult;
import com.youkeda.project.tool.ToolChatResult;
import com.youkeda.project.tool.ToolExecutionRequest;
import com.youkeda.project.tool.ToolExecutionResult;
import com.youkeda.project.tool.ToolOrchestrator;
import com.youkeda.project.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class QwenChatService {

    private static final Logger log = LoggerFactory.getLogger(QwenChatService.class);

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${llm.text-model:qwen-plus}")
    private String textModel;

    @Value("${llm.vision-model:qwen-vl-plus}")
    private String visionModel;

    @Value("${llm.max-tokens:1024}")
    private int maxTokens;

    @Value("${llm.tool-max-rounds:5}")
    private int maxToolRounds;

    @Value("${llm.timeout-seconds:60}")
    private long timeoutSeconds;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey = "";

    private final ToolRegistry toolRegistry;
    private final ToolOrchestrator toolOrchestrator;   // 新增

    public QwenChatService(ToolRegistry toolRegistry, ToolOrchestrator toolOrchestrator) {
        this.toolRegistry = toolRegistry;
        this.toolOrchestrator = toolOrchestrator;
    }

    @PostConstruct
    void init() {
        this.apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY is not configured; LLM reply is disabled");
        }
    }

    @PreDestroy
    void shutdown() {
        if (toolOrchestrator != null) {
            toolOrchestrator.shutdown();
        }
    }

    public String chat(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage is required");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage());
        messages.add(Map.of("role", "user", "content", userMessage.trim()));
        return complete(textModel, messages);
    }

    public String chatWithSearch(String userMessage, String searchResults) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage is required");
        }
        String context = searchResults == null || searchResults.isBlank()
                ? "暂无相关搜索结果。"
                : searchResults.trim();

        StringBuilder prompt = new StringBuilder();
        prompt.append("请参考以下实时搜索结果回答问题，如果搜索结果中没有相关信息，请明确告知用户。\n\n");
        prompt.append("=== 搜索结果 ===\n");
        prompt.append(context);
        prompt.append("\n\n=== 用户问题 ===\n");
        prompt.append(userMessage.trim());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage());
        messages.add(Map.of("role", "user", "content", prompt.toString()));
        return complete(textModel, messages);
    }

    public String chatWithImage(byte[] imageBytes, String prompt) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes is required");
        }
        String imagePrompt =
                prompt == null || prompt.isBlank() ? "Describe this image." : prompt.trim();
        String dataUrl =
                "data:"
                        + detectMimeType(imageBytes)
                        + ";base64,"
                        + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUrl);
        imagePart.put("image_url", imageUrl);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", imagePrompt);

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(imagePart);
        content.add(textPart);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage());
        messages.add(Map.of("role", "user", "content", content));
        return complete(visionModel, messages);
    }

    public ToolChatResult chatWithTools(String userMessage) {
        return chatWithTools(null, null, userMessage);
    }

    public ToolChatResult chatWithTools(
            String systemPrompt, List<Map<String, Object>> history, String userMessage) {
        return chatWithTools(systemPrompt, history, userMessage, maxToolRounds);
    }

    /**
     * 自主规划型 Agent 入口：支持自定义最大工具轮次，供需要多步拆解的长任务使用
     * （如旅行规划：查天气→景点→美食→车票酒店→编排→生成手册，需 >5 轮）。
     */
    public ToolChatResult chatWithTools(
            String systemPrompt,
            List<Map<String, Object>> history,
            String userMessage,
            int maxRounds) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage is required");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(
                systemPrompt == null || systemPrompt.isBlank()
                        ? systemMessage()
                        : systemMessage(systemPrompt));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Map.of("role", "user", "content", userMessage.trim()));
        return completeWithTools(textModel, messages, maxRounds);
    }

    private ToolChatResult completeWithTools(
            String model, List<Map<String, Object>> messages, int maxRounds) {
        if (toolRegistry == null) {
            throw new LlmException("ToolRegistry is not configured");
        }
        if (toolOrchestrator == null) {
            throw new LlmException("ToolOrchestrator is not configured");
        }
        List<String> calledTools = new ArrayList<>();
        String imageUrl = null;
        List<String> lastRoundTools = null;
        // 缓存最近一次「成品型工具」的执行结果（用于连续重复调用时直接复用，
        // 避免再让 LLM 自由发挥写出串台/幻觉的正文）。
        String lastBuilderContent = null;

        for (int round = 0; round <= maxRounds; round++) {
            JsonNode message = postChat(model, messages, true);
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = message.path("content").asText(null);
                if (content == null || content.isBlank()) {
                    throw new LlmException("Qwen tool chat returned an empty response");
                }
                return new ToolChatResult(content.trim(), imageUrl, calledTools);
            }
            if (round >= maxRounds) {
                throw new LlmException(
                        "Qwen tool chat exceeded max tool rounds: " + maxRounds);
            }

            // 将助手消息（含tool_calls）加入历史
            messages.add(toAssistantMessage(message, toolCalls));

            // --- 改造开始：并行执行所有工具调用 ---
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (JsonNode toolCall : toolCalls) {
                String toolCallId = toolCall.path("id").asText("");
                String functionName = toolCall.path("function").path("name").asText("");
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                if (!isValidJson(arguments)) {
                    log.warn("tool_call [{}] arguments 非法 JSON，执行时用 {{}} 兜底", functionName);
                    arguments = "{}";
                }
                if (toolCallId.isBlank() || functionName.isBlank()) {
                    continue;
                }
                requests.add(new ToolExecutionRequest(toolCallId, functionName, arguments));
            }

            // 连续重复工具调用检测：若本轮调用的工具名集合与上一轮完全相同，
            // 说明 LLM 陷入循环（例如反复 build_itinerary）。此时强制结束工具调用，
            // 注入一条提示并做一次不含工具的最终补全，保证流程一定收敛。
            List<String> currentRoundTools = new ArrayList<>();
            for (ToolExecutionRequest r : requests) {
                currentRoundTools.add(r.name());
            }
            if (currentRoundTools.equals(lastRoundTools)) {
                log.warn("连续两轮调用相同工具 {}，终止工具调用流程", currentRoundTools);
                // 若上一轮已经产出过 build_itinerary / 成品型工具的结果，直接复用，
                // 不再让 LLM 自由写正文（否则极易把上下文别的城市/别的会话内容串台）。
                if (lastBuilderContent != null && !lastBuilderContent.isBlank()) {
                    log.info("直接复用上一轮成品工具结果作为最终回复");
                    return new ToolChatResult(lastBuilderContent.trim(), imageUrl, calledTools);
                }
                messages.add(Map.of(
                        "role", "user",
                        "content", "请基于以上已收集的信息直接给出最终回复，不要再调用任何工具。"));
                JsonNode finalMessage = postChat(model, messages, false);
                String finalContent = finalMessage.path("content").asText(null);
                if (finalContent == null || finalContent.isBlank()) {
                    throw new LlmException(
                            "Qwen tool chat returned an empty response after duplicate tool detection");
                }
                return new ToolChatResult(finalContent.trim(), imageUrl, calledTools);
            }
            lastRoundTools = currentRoundTools;

            // 使用编排器并行执行
            List<ToolExecutionResult> results = toolOrchestrator.executeParallel(requests);

            // 将每个工具的执行结果添加到消息历史
            for (ToolExecutionResult result : results) {
                calledTools.add(result.name());
                if (result.imageUrl() != null && !result.imageUrl().isBlank()) {
                    imageUrl = result.imageUrl();
                }
                String content = result.isSuccess()
                        ? (result.content() == null ? "" : result.content())
                        : "工具执行失败: " + (result.error() == null ? "未知错误" : result.error().getMessage());
                // 记录成品型工具的最近返回（完整内容，不截断），作为收敛兜底
                if ("build_itinerary".equals(result.name()) && result.isSuccess()) {
                    lastBuilderContent = content;
                }
                // 截断工具结果再回传 LLM，避免上下文膨胀导致 token 消耗激增
                messages.add(toolResultMessage(result.id(), truncateForContext(content, 1200)));
            }
            // --- 改造结束 ---

            // 继续下一轮（将工具结果发送给LLM）
        }
        throw new LlmException("Qwen tool chat did not produce a final reply");
    }

    private String complete(String model, List<Map<String, Object>> messages) {
        if (apiKey.isBlank()) {
            throw new LlmException("DASHSCOPE_API_KEY is not configured");
        }
        JsonNode message = postChat(model, messages, false);
        String content = message.path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new LlmException("Qwen API returned an empty response");
        }
        return content.trim();
    }

    private JsonNode postChat(
            String model, List<Map<String, Object>> messages, boolean includeTools) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", maxTokens);
            if (includeTools) {
                requestBody.put("tools", toolRegistry.definitions());
                requestBody.put("tool_choice", "auto");
            }

            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                            .timeout(Duration.ofSeconds(timeoutSeconds))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn(
                        "Qwen API returned HTTP {} for model {}",
                        response.statusCode(),
                        model);
                throw new LlmException(
                        "Qwen API error: HTTP "
                                + response.statusCode()
                                + " "
                                + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode() || message.isNull()) {
                throw new LlmException("Qwen API returned no message");
            }
            return message;
        } catch (IOException e) {
            throw new LlmException("Qwen API request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Qwen API request interrupted", e);
        }
    }

    private Map<String, Object> systemMessage() {
        return systemMessage(
                "You are a helpful WeChat assistant. "
                        + "When the user asks about the current time/date (e.g. 几点了/今天几号/星期几), "
                        + "always call the get_time tool instead of answering from memory. "
                        + "When the user asks about weather for a city, always call the query_weather tool. "
                        + "Use image generation tools when the user asks to draw/generate an image. "
                        + "Answer concisely in the same language as the user.");
    }

    private Map<String, Object> systemMessage(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", content);
        return message;
    }

    private Map<String, Object> toAssistantMessage(
            JsonNode message, JsonNode toolCalls) {
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", message.path("content").asText(null));
        // 清洗 tool_calls：保证每个 function.arguments 是合法 JSON 字符串，
        // 避免 LLM 偶发返回非 JSON 格式 arguments 导致下一轮回传 DashScope HTTP 400。
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (JsonNode tc : toolCalls) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", tc.path("id").asText(""));
            entry.put("type", tc.path("type").asText("function"));
            Map<String, Object> fn = new LinkedHashMap<>();
            JsonNode func = tc.path("function");
            fn.put("name", func.path("name").asText(""));
            String args = func.path("arguments").asText("");
            if (!isValidJson(args)) {
                log.warn("tool_call [{}] arguments 非法 JSON，回传时用 {{}} 兜底", fn.get("name"));
                args = "{}";
            }
            fn.put("arguments", args);
            entry.put("function", fn);
            cleaned.add(entry);
        }
        assistantMessage.put("tool_calls", cleaned);
        return assistantMessage;
    }

    private boolean isValidJson(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            objectMapper.readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> toolResultMessage(String toolCallId, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", toolCallId);
        message.put("content", content == null ? "" : content);
        return message;
    }

    /** 截断工具结果，防止 RAG/搜索返回的长文本撑爆上下文窗口。 */
    private static String truncateForContext(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) return content;
        return content.substring(0, maxChars) + "\n...(已截断，仅保留前" + maxChars + "字)";
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 4
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == '8') {
            return "image/gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    private String resolveApiKey() {
        String envKey = System.getenv("DASHSCOPE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        for (String fileName : List.of(".env.local", ".env")) {
            Path file = Path.of(fileName);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("DASHSCOPE_API_KEY=")) {
                        return trimmed.substring("DASHSCOPE_API_KEY=".length()).trim();
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", fileName, e.getMessage());
            }
        }
        return "";
    }
}