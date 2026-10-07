package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.map.AmapService;
import com.youkeda.project.map.AmapService.Point;
import com.youkeda.project.map.AmapService.StaticMapResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高德地图静态图工具（function-calling 名 = static_map）。
 *
 * <p>LLM 先 geocode 拿到景点坐标，再把 points=[{name,lng,lat}] 传入 static_map，
 * 工具会生成一张带起点(S绿)、终点(E红)、途经点(蓝数字)、折线连线的 PNG 图片，
 * 缓存到本地并返回 Markdown 图片链接，直接贴进行程手册即可展示。
 */
@Component
public class AmapStaticMapTool implements AgentTool {

    private static final String TOOL_NAME = "static_map";

    private final AmapService amapService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AmapStaticMapTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String description() {
        return "生成高德地图静态 PNG 图片（彩色标注+路径连线）。调用前必须先用 geocode 把景点名/地址转成 lng/lat 坐标。"
                + "图片缓存在本地，通过 GET /api/amap/static/{cacheKey}.png 代理访问，可直接在 Markdown 手册中用「![标题](URL)」插入。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of(
                "type", "string",
                "description", "图片展示标题，如「成都 Day1 路线图」「上海总览」"));
        // points = array of {name,lng,lat}
        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", Map.of(
                "name", Map.of("type", "string", "description", "点位名称（展示），如「宽窄巷子」"),
                "lng",  Map.of("type", "number", "description", "经度（必须来自 geocode），如 104.061"),
                "lat",  Map.of("type", "number", "description", "纬度（必须来自 geocode），如 30.673")
        ));
        itemSchema.put("required", List.of("lng", "lat"));
        properties.put("points", Map.of(
                "type", "array",
                "description", "坐标点数组。1个点仅标注；>=2个点自动画折线（起点绿S/终点红E/中间蓝数字）。",
                "items", itemSchema
        ));
        properties.put("zoom", Map.of(
                "type", "integer",
                "description", "缩放等级 3-18，0 表示由高德根据标注自动选择（推荐 0，最智能）",
                "enum", new int[]{0, 10, 11, 12, 13, 14, 15}
        ));
        properties.put("size_w", Map.of(
                "type", "integer",
                "description", "图片宽度（200-1024，默认 800）"
        ));
        properties.put("size_h", Map.of(
                "type", "integer",
                "description", "图片高度（200-1024，默认 500）"
        ));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", properties);
        out.put("required", List.of("points"));
        return out;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            String title = text(args, "title", "高德路线图");
            int zoom = asInt(args, "zoom", 0);
            int w = asInt(args, "size_w", 800);
            int h = asInt(args, "size_h", 500);
            JsonNode ptsNode = args.path("points");
            if (ptsNode.isMissingNode() || ptsNode.isNull() || !ptsNode.isArray()) {
                return AgentToolResult.text("static_map 参数错误：points 必须是数组 [{name,lng,lat}]。");
            }
            List<Point> pts = new ArrayList<>();
            for (JsonNode p : ptsNode) {
                pts.add(new Point(text(p, "name", "P"),
                        p.path("lng").asDouble(0),
                        p.path("lat").asDouble(0)));
            }
            if (pts.isEmpty()) return AgentToolResult.text("static_map 参数错误：points 为空。请至少传 1 个点。");
            StaticMapResult r = amapService.staticMap(title, pts, zoom, w, h);
            if (!r.ok()) return AgentToolResult.text("static_map 失败：" + r.error());
            StringBuilder sb = new StringBuilder();
            sb.append("静态地图生成成功\n");
            sb.append("  标题：").append(title).append("\n");
            sb.append("  点数：").append(pts.size()).append("（起点绿S/终点红E/途经蓝数字）\n");
            for (int i = 0; i < Math.min(pts.size(), 6); i++) {
                sb.append("  - ").append(pts.get(i).name()).append(" (")
                  .append(pts.get(i).lng()).append(",").append(pts.get(i).lat()).append(")\n");
            }
            if (pts.size() > 6) sb.append("  ... 共 ").append(pts.size()).append(" 点\n");
            sb.append("  图片预览 URL（直接浏览器访问或 Markdown 使用）：\n");
            sb.append("    ").append(r.previewUrl()).append("\n");
            sb.append("  Markdown 写法（推荐贴在每日行程下方展示）：\n");
            sb.append("    ![").append(mdEscape(title)).append("](").append(r.previewUrl()).append(")\n");
            sb.append("  缓存 Key：").append(r.cacheKey()).append(".png（30天强缓存，sha256 唯一不变）\n");
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("static_map 参数解析或执行异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ================= helpers =================

    private static String text(JsonNode node, String field, String def) {
        if (node == null) return def;
        JsonNode f = node.path(field);
        String v = (f.isMissingNode() || f.isNull()) ? null : f.asText();
        return (v == null || v.isBlank()) ? def : v;
    }
    private static int asInt(JsonNode node, String field, int def) {
        if (node == null) return def;
        JsonNode f = node.path(field);
        if (f.isMissingNode() || f.isNull()) return def;
        if (f.isNumber()) return f.asInt(def);
        try { return Integer.parseInt(f.asText().trim()); } catch (Exception e) { return def; }
    }
    private static String mdEscape(String s) { return s == null ? "" : s.replace("]", "\\]").replace("\n", " "); }
}
