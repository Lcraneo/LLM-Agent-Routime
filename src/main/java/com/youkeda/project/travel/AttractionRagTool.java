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
 * 旅行工具 1：景点 RAG 检索（全国范围）。
 *
 * <p>复用 {@link RagService}，从 knowledge-base/travel-attractions.txt 按「城市+关键词」
 * 召回景点块，覆盖上海、北京、杭州、成都、西安、广州、重庆、厦门、丽江、桂林等
 * 全国主要旅游城市，返回区域/开放时间/门票/游玩时长/标签等结构化信息，供 LLM 编排行程。
 */
@Component
public class AttractionRagTool implements AgentTool {

    private static final List<String> COVERED_CITIES = List.of(
            "上海", "北京", "杭州", "成都", "西安", "广州", "重庆",
            "厦门", "丽江", "桂林");

    private final RagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AttractionRagTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "search_attractions";
    }

    @Override
    public String description() {
        return "从全国景点知识库检索景点信息，返回景点的城市、区域、开放时间、门票价格、"
                + "游玩时长、标签等结构化信息，用于行程规划。"
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
        properties.put("region", Map.of(
                "type", "string",
                "description", "可选，限定城区/片区，如 西湖、浦东、渝中、阳朔"));
        properties.put("keyword", Map.of(
                "type", "string",
                "description", "可选，景点类型/关键词，如 迪士尼、长城、西湖、熊猫、兵马俑、夜景、古镇"));
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
            String region = args.path("region").asText("").trim();
            String keyword = args.path("keyword").asText("").trim();

            if (city.isBlank()) {
                return AgentToolResult.text(
                        "缺少必填参数 city（目的地城市）。可支持：" + String.join("、", COVERED_CITIES) + "。");
            }

            String query = String.join(" ", city, region, keyword).trim();

            // Step 1: 先用 RagService 关键词召回
            List<RagChunk> raw = new ArrayList<>(ragService.retrieve(query));
            // 注意：语义召回只返回 top-N，可能命中了其他源（非景点）导致后面过滤全空，
            // 所以这里先做一次快速过滤；若结果不足，再用 allChunks 全量兜底。
            List<RagChunk> chunks = filterChunks(raw, city, region, keyword);

            if (chunks.size() < 3) {
                List<RagChunk> fallback = filterChunks(ragService.allChunks(), city, region, keyword);
                // 合并去重（按内容）
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
                hint.append("景点知识库中未找到「")
                        .append(query)
                        .append("」相关景点。");
                if (!COVERED_CITIES.contains(city)) {
                    hint.append("\n知识库暂未覆盖「")
                            .append(city)
                            .append("」（已覆盖：")
                            .append(String.join("、", COVERED_CITIES))
                            .append("），建议改用 web_search 联网搜索该城市景点信息。");
                } else {
                    hint.append("\n可尝试换关键词，或调用 web_search 联网补充实时信息。");
                }
                return AgentToolResult.text(hint.toString());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("在「").append(city).append("」找到 ")
                    .append(chunks.size()).append(" 个相关景点：\n\n");
            for (RagChunk c : chunks) {
                sb.append("--- 来源：").append(c.source()).append(" ---\n")
                        .append(c.content().strip()).append("\n\n");
            }
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("景点检索失败：" + e.getMessage());
        }
    }

    /** 统一过滤：源文件=attraction + 城市命中 + region/keyword 命中。 */
    private List<RagChunk> filterChunks(
            List<RagChunk> input, String city, String region, String keyword) {
        return input.stream()
                .filter(c -> c.source().toLowerCase().contains("attraction"))
                .filter(c -> containsCity(c.content(), city))
                .filter(c -> region.isBlank()
                        || c.content().toLowerCase().contains(region.toLowerCase()))
                .filter(c -> keyword.isBlank()
                        || c.content().toLowerCase().contains(keyword.toLowerCase()))
                .collect(Collectors.toList());
    }

    /** 匹配 chunk 头部的「城市：xxx」标记，避免正文引用别的城市被误吸。 */
    private static boolean containsCity(String content, String city) {
        if (content == null || city == null) return false;
        String marker = "城市：" + city;
        if (content.startsWith(marker)) return true;
        return content.contains("\n" + marker) || content.contains("\r\n" + marker);
    }
}
