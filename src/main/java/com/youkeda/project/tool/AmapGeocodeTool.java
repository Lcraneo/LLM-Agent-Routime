package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.map.AmapService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高德地图 Tool 1：地理编码 &amp; 逆地理编码。
 *
 * <ul>
 *   <li>地址 → 经纬度（用于后续路径规划、距离测量、周边搜索）</li>
 *   <li>经纬度 → 地址（确认定位结果）</li>
 * </ul>
 */
@Component
public class AmapGeocodeTool implements AgentTool {

    private final AmapService amapService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AmapGeocodeTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "geocode";
    }

    @Override
    public String description() {
        return "地理编码：将中文地址/地点名转换为经纬度坐标（格式 经度,纬度），"
                + "或反向将经纬度翻译为结构化地址。"
                + "调用路径规划、距离测量、周边搜索等工具前，"
                + "应先使用本工具获取起/终点的经纬度坐标。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of(
                "type", "string",
                "enum", new String[]{"geo", "regeo"},
                "description", "geo=地址转经纬度，regeo=经纬度转地址，默认 geo"));
        properties.put("address", Map.of(
                "type", "string",
                "description", "mode=geo 时必填，中文地址或地点名，如「外滩观景台」「成都双流机场」"));
        properties.put("city", Map.of(
                "type", "string",
                "description", "mode=geo 时可选，限定城市名，如「上海」「北京」，提高解析准确率"));
        properties.put("location", Map.of(
                "type", "string",
                "description", "mode=regeo 时必填，坐标格式「经度,纬度」，如 121.490494,31.239662"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("mode"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String mode = args.path("mode").asText("geo");
            if ("regeo".equals(mode)) {
                String loc = args.path("location").asText("");
                if (loc.isBlank()) return AgentToolResult.text("mode=regeo 时必须传 location 参数。");
                String result = amapService.reverseGeocode(loc);
                if (result == null) {
                    return AgentToolResult.text(amapService.isConfigured()
                            ? "逆地理编码失败，请检查经纬度格式（应为 经度,纬度）。"
                            : "【提示】未配置高德 API Key，请在 application.properties 设置 amap.api-key。");
                }
                return AgentToolResult.text("坐标 " + loc + " 对应地址：\n" + result);
            } else {
                String address = args.path("address").asText("");
                if (address.isBlank()) return AgentToolResult.text("mode=geo 时必须传 address 参数。");
                String city = args.path("city").asText(null);
                String result = amapService.geocode(address, city);
                if (result == null) {
                    return AgentToolResult.text(amapService.isConfigured()
                            ? "未能解析「" + address + "」的坐标，请换用更精确的地址名。"
                            : "【提示】未配置高德 API Key，无法进行地理编码。"
                            + "请在 application.properties 设置 amap.api-key。");
                }
                return AgentToolResult.text("「" + address + (city == null ? "" : "(" + city + ")")
                        + "」的坐标：" + result);
            }
        } catch (Exception e) {
            return AgentToolResult.text("地理编码参数解析失败：" + e.getMessage());
        }
    }
}
