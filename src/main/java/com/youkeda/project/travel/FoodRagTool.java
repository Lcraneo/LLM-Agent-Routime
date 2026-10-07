package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.rag.RagChunk;
import com.youkeda.project.rag.RagService;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 2：美食 RAG 检索（全国范围）。
 *
 * <p>复用 {@link RagService}，从 knowledge-base/travel-food.txt 召回目标城市的
 * 代表餐厅/菜系/人均/推荐菜信息。已覆盖全国主要旅游城市（见 COVERED_CITIES）。
 */
@Component
public class FoodRagTool implements AgentTool {

    private static final List<String> COVERED_CITIES = List.of(
            "上海", "北京", "杭州", "成都", "西安", "广州", "重庆",
            "厦门", "丽江", "桂林");

    private final RagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FoodRagTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "search_foods";
    }

    @Override
    public String description() {
        return "从全国美食知识库检索餐厅、菜系、人均、推荐菜等信息，用于行程餐饮规划。"
                + "已覆盖城市：" + String.join("、", COVERED_CITIES)
                + "；若知识库中没有该城市信息，本工具会提示你改用 web_search 联网补充。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", Map.of(
                "type", "string",
                "description",
                "目的地城市名，必填，如 北京、杭州、成都、西安、广州、重庆、厦门、丽江、桂林"));
        properties.put("keyword", Map.of(
                "type", "string",
                "description",
                "可选，美食关键词，如 烤鸭、火锅、早茶、小笼、米粉、沙茶面、小吃、正餐"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("city"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String city = args.path("city").asText("").trim();
            String keyword = args.path("keyword").asText("").trim();

            if (city.isBlank()) {
                return AgentToolResult.text(
                        "缺少必填参数 city（目的地城市）。可支持："
                                + String.join("、", COVERED_CITIES) + "。");
            }

            String query = String.join(" ", city, keyword).trim();

            // 先用语义召回，不足时再全量扫库兜底（避免语义召回命中其他源导致 food 全空）
            List<RagChunk> raw = new ArrayList<>(ragService.retrieve(query));
            List<RagChunk> chunks = filterFood(raw, city, keyword);
            if (chunks.size() < 3) {
                List<RagChunk> fallback = filterFood(ragService.allChunks(), city, keyword);
                Set<String> seen = new HashSet<>();
                List<RagChunk> merged = new ArrayList<>();
                for (RagChunk c : chunks) {
                    String k = c.content().trim();
                    if (seen.add(k)) merged.add(c);
                }
                for (RagChunk c : fallback) {
                    String k = c.content().trim();
                    if (seen.add(k)) merged.add(c);
                }
                chunks = merged.stream().limit(10)
                        .map(c -> RagChunk.of(c.source(), c.content(), 0))
                        .collect(Collectors.toList());
            } else {
                chunks = chunks.stream().limit(10)
                        .map(c -> RagChunk.of(c.source(), c.content(), 0))
                        .collect(Collectors.toList());
            }

            if (chunks.isEmpty()) {
                StringBuilder hint = new StringBuilder();
                hint.append("美食知识库中未找到「").append(query).append("」相关内容。");
                if (!COVERED_CITIES.contains(city)) {
                    hint.append("\n知识库暂未覆盖「")
                            .append(city)
                            .append("」（已覆盖：")
                            .append(String.join("、", COVERED_CITIES))
                            .append("），建议改用 web_search 联网搜索该城市特色美食。");
                } else {
                    hint.append("\n可尝试换关键词，或调用 web_search 联网补充实时餐厅推荐。");
                }
                return AgentToolResult.text(hint.toString());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("在「").append(city).append("」找到 ")
                    .append(chunks.size()).append(" 条美食推荐：\n\n");
            for (RagChunk c : chunks) {
                sb.append("--- 来源：").append(c.source()).append(" ---\n")
                        .append(c.content().strip()).append("\n\n");
            }
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("美食检索失败：" + e.getMessage());
        }
    }

    /** 美食统一过滤：源=food + 城市命中 + keyword 命中。 */
    private List<RagChunk> filterFood(List<RagChunk> input, String city, String keyword) {
        return input.stream()
                .filter(c -> c.source().toLowerCase().contains("food"))
                .filter(c -> containsCity(c.content(), city))
                .filter(c -> keyword.isBlank()
                        || c.content().toLowerCase().contains(keyword.toLowerCase()))
                .collect(Collectors.toList());
    }

    private static boolean containsCity(String content, String city) {
        if (content == null || city == null) return false;
        // 严格按知识库 chunk 开头标记「城市：XX」匹配，避免正文里
        // "与上海流派不同" / "与北京风格相似" 等跨城引用被误吸。
        String marker = "城市：" + city;
        if (content.startsWith(marker)) return true;
        return content.contains("\n" + marker) || content.contains("\r\n" + marker);
    }
}
