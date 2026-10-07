package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.search.SearchService;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 3：联网搜索（Tavily）。
 *
 * <p>复用 {@link SearchService}，补足 RAG 之外的实时信息：往返车票/机票、酒店、近期活动、
 * 景点最新资讯。实时天气交由已注册的 query_weather 工具，不在此重复。
 */
@Component
public class TravelSearchTool implements AgentTool {

    private final SearchService searchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelSearchTool(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索实时信息，如往返高铁/机票、酒店预订、近期热门活动、景点最新资讯。"
                + "注意：实时天气请改用 query_weather 工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description",
                "搜索关键词，如 北京三日游酒店推荐、北京到杭州往返高铁、"
                        + "成都近期展览、西安长恨歌演出订票、厦门鼓浪屿船票攻略"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("query"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String query = args.path("query").asText("");
            if (query.isBlank()) {
                return AgentToolResult.text("缺少搜索关键词 query。");
            }
            String result = searchService.search(query);
            if (result == null || result.isBlank()) {
                return AgentToolResult.text("未搜索到「" + query + "」的相关结果（可能未配置 TAVILY_API_KEY）。");
            }
            return AgentToolResult.text("搜索「" + query + "」结果：\n" + result);
        } catch (Exception e) {
            return AgentToolResult.text("联网搜索失败：" + e.getMessage());
        }
    }
}
