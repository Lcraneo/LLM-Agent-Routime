package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.weather.WeatherService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements AgentTool {

    private final WeatherService weatherService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 简单内存缓存：同一城市+类型 30 分钟内复用，减少 API 调用
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    private record CacheEntry(String content, long timestamp) {}
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String name() {
        return "query_weather";
    }

    @Override
    public String description() {
        return "查询指定城市的实时天气或未来天气预报，返回天气现象、温度、湿度、风向风力等信息。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
            "location",
            Map.of(
                "type", "string",
                "description", "城市名、城市拼音或经纬度，例如 beijing、上海、22.2268:113.4852"));
        properties.put(
            "type",
            Map.of(
                "type", "string",
                "enum", new String[] {"now", "daily"},
                "description", "查询类型：now 为实时天气，daily 为天气预报，默认 now"));
        properties.put(
            "days",
            Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 15,
                "description", "天气预报天数，仅在 type=daily 时生效，默认 3"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("location"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String location = args.path("location").asText("");
            if (location.isBlank()) {
                return AgentToolResult.text("缺少地点参数 location。");
            }
            String type = args.path("type").asText("now");
            int days = args.path("days").asInt(3);
            String cacheKey = location + ":" + type + ":" + days;
            CacheEntry cached = CACHE.get(cacheKey);
            if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
                return AgentToolResult.text(cached.content());
            }
            String result;
            if ("daily".equals(type)) {
                result = weatherService.describeForecast(location, days);
            } else {
                result = weatherService.describeNow(location);
            }
            CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            return AgentToolResult.text(result);
        } catch (Exception e) {
            return AgentToolResult.text("天气查询参数解析失败：" + e.getMessage());
        }
    }
}
