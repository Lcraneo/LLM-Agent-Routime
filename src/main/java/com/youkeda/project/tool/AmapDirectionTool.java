package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.map.AmapService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高德地图 Tool 3：路径规划 + 距离测量。
 *
 * <p>支持四种出行方式：驾车(driving)、步行(walking)、骑行(bicycling)、公交(transit)。
 * 入参 origin/destination 需为「经度,纬度」格式，调用前请先用 geocode 工具转换。
 * mode=distance 时只返回距离+耗时摘要，不返回分段路线（更省 token）。
 */
@Component
public class AmapDirectionTool implements AgentTool {

    private final AmapService amapService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapDirectionTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "plan_route";
    }

    @Override
    public String description() {
        return "两点之间路线规划或距离测量。支持：驾车、步行、骑行、公交四种出行方式。"
                + "起终点必须是「经度,纬度」格式（先用 geocode 工具把地址/景点名转为坐标）。"
                + "mode=distance 可以快速获得距离+耗时，配合滴滴打车费用预估工具使用。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of(
                "type", "string",
                "enum", new String[]{"driving", "walking", "bicycling", "transit", "distance"},
                "description", "出行方式：driving=驾车 walking=步行 bicycling=骑行 transit=公交 distance=仅测距离/耗时，默认 driving"));
        properties.put("origin", Map.of(
                "type", "string",
                "description", "起点坐标「经度,纬度」，如 121.490494,31.239662。"
                        + "先用 geocode 工具把地址转为坐标"));
        properties.put("destination", Map.of(
                "type", "string",
                "description", "终点坐标「经度,纬度」，同上"));
        properties.put("city", Map.of(
                "type", "string",
                "description", "mode=transit 时必填，公交所在城市名，如 上海、北京"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("origin", "destination"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String mode = args.path("mode").asText("driving");
            String origin = args.path("origin").asText("");
            String dest = args.path("destination").asText("");
            if (origin.isBlank() || dest.isBlank()) {
                return AgentToolResult.text("缺少起点 origin 或终点 destination（需传 经度,纬度）。");
            }
            String result;
            switch (mode) {
                case "walking" -> result = amapService.directionWalking(origin, dest);
                case "bicycling" -> result = amapService.directionBicycling(origin, dest);
                case "transit" -> {
                    String city = args.path("city").asText("");
                    if (city.isBlank()) {
                        return AgentToolResult.text("mode=transit 公交规划时必须传 city 参数（所在城市）。");
                    }
                    result = amapService.directionTransit(origin, dest, city);
                }
                case "distance" -> result = amapService.measureDistance(origin, dest);
                default -> result = amapService.directionDriving(origin, dest);
            }
            return AgentToolResult.text(result);
        } catch (Exception e) {
            return AgentToolResult.text("路径规划参数解析失败：" + e.getMessage());
        }
    }
}
