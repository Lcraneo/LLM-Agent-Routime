package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.map.AmapService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高德地图 Tool 2：POI 搜索（关键字 + 周边）。
 *
 * <p>典型用途：
 * <ul>
 *   <li>查「成都春熙路附近火锅」→ 选餐厅</li>
 *   <li>查「迪士尼停车场」→ 获取具体坐标后做路径规划</li>
 *   <li>查景点周边酒店、便利店、ATM、充电桩</li>
 * </ul>
 */
@Component
public class AmapPoiTool implements AgentTool {

    private final AmapService amapService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapPoiTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "search_poi";
    }

    @Override
    public String description() {
        return "基于高德地图搜索地点(POI)。支持两种模式："
                + "(1) 关键字搜索 mode=text：按关键词+城市直接查地点，如 上海外滩景点；"
                + "(2) 周边搜索 mode=around：以某个经纬度为圆心，搜半径内的指定类型 POI。"
                + "返回结果包含名称、区域、详细地址、坐标、评分、人均消费、营业时间、电话等。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of(
                "type", "string",
                "enum", new String[]{"text", "around"},
                "description", "text=关键字搜索，around=周边搜索，默认 text"));
        properties.put("keywords", Map.of(
                "type", "string",
                "description", "搜索关键字。text 模式如「火锅」「景点」「酒店」「外滩」；"
                        + "around 模式可传类型过滤，如「餐厅」「ATM」，传空则搜全部"));
        properties.put("city", Map.of(
                "type", "string",
                "description", "text 模式可选，限定搜索城市，如 成都"));
        properties.put("location", Map.of(
                "type", "string",
                "description", "around 模式必填，圆心坐标 经度,纬度，需先用 geocode 工具转换"));
        properties.put("radius", Map.of(
                "type", "integer",
                "description", "around 模式可选，搜索半径(米)，100-50000，默认 1500"));
        properties.put("page_size", Map.of(
                "type", "integer",
                "description", "返回条数，1-25，默认 8"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("mode", "keywords"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String mode = args.path("mode").asText("text");
            String keywords = args.path("keywords").asText("");
            int pageSize = args.path("page_size").asInt(8);
            if ("around".equals(mode)) {
                String loc = args.path("location").asText("");
                if (loc.isBlank()) return AgentToolResult.text("mode=around 时必须传 location（圆心坐标 经度,纬度）。");
                int radius = args.path("radius").asInt(1500);
                return AgentToolResult.text(amapService.searchPoiAround(loc, radius, keywords));
            } else {
                String city = args.path("city").asText(null);
                return AgentToolResult.text(amapService.searchPoiText(keywords, city, pageSize));
            }
        } catch (Exception e) {
            return AgentToolResult.text("POI 搜索参数解析失败：" + e.getMessage());
        }
    }
}
