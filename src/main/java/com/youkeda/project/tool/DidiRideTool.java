package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.ride.DidiRideService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 滴滴打车 Tool：费用预估 + 车型选型建议。
 *
 * <h3>两种使用方式</h3>
 * <ol>
 *   <li><b>mode=estimate（预估价格）</b>：传入 city、distance_km、duration_min 以及可选车型列表，
 *       返回全车型的价格区间、时段动调、出行策略。</li>
 *   <li><b>mode=recommend（选型建议）</b>：根据人数、预算、场景、距离、城市，
 *       返回最优车型 + 高峰期叫车锦囊。</li>
 * </ol>
 *
 * <p>无需配置 API Key 即可运行（本地费率模型）。若配置 didi.client-id + didi.access-token，
 * estimate 模式会优先尝试调用滴滴官方 API 返回精确预估价，失败自动降级。
 */
@Component
public class DidiRideTool implements AgentTool {

    private final DidiRideService didiRideService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DidiRideTool(DidiRideService didiRideService) {
        this.didiRideService = didiRideService;
    }

    @Override
    public String name() {
        return "estimate_ride";
    }

    @Override
    public String description() {
        return "滴滴打车费用预估与车型选型。两个模式："
                + "(1) mode=estimate：估算各车型价格区间，需要 city 城市名 + distance_km 公里数 "
                + "+ duration_min 分钟数（先用 plan_route mode=distance 或 plan_route mode=driving 获取距离和耗时）；"
                + "(2) mode=recommend：根据人数、预算、场景给出最优车型建议和叫车策略。"
                + "无需配置 API Key 即可给出靠谱估算；配置 didi 凭证后可调用官方 API 返回精确到分的预估价。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mode", Map.of(
                "type", "string",
                "enum", new String[]{"estimate", "recommend"},
                "description", "estimate=预估价格，recommend=选型建议，默认 estimate"));
        // estimate 参数
        properties.put("city", Map.of(
                "type", "string",
                "description", "【estimate必填】城市名，支持：北京上海广州深圳杭州成都重庆西安武汉南京苏州天津长沙青岛厦门昆明桂林丽江"));
        properties.put("distance_km", Map.of(
                "type", "number",
                "description", "【estimate必填】行程公里数，建议先用 plan_route 获取"));
        properties.put("duration_min", Map.of(
                "type", "integer",
                "description", "【estimate推荐】行程分钟数，不传则按距离粗略估算"));
        properties.put("products", Map.of(
                "type", "array",
                "description", "【estimate可选】要预估的车型名数组，如 [\"快车\",\"优享\"]；不传则预估全部 9 款车型",
                "items", Map.of("type", "string")));
        properties.put("hour_of_day", Map.of(
                "type", "integer",
                "description", "【estimate可选】出发小时 0-23，用于计算时段动调；传 -1 视为当前时间"));
        properties.put("day_of_week", Map.of(
                "type", "integer",
                "description", "【estimate可选】出发星期 1-7 (1=周一)；传 -1 视为今天"));
        // recommend 参数
        properties.put("people_count", Map.of(
                "type", "integer",
                "description", "【recommend必填】出行人数"));
        properties.put("budget_per_person", Map.of(
                "type", "number",
                "description", "【recommend推荐】人均预算（元），用于匹配档位"));
        properties.put("scenario", Map.of(
                "type", "string",
                "description", "【recommend可选】出行场景：日常/商务/接待/VIP/家庭/旅游 等"));
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
            String mode = args.path("mode").asText("estimate");
            if ("recommend".equals(mode)) {
                int people = args.path("people_count").asInt(-1);
                if (people <= 0) return AgentToolResult.text("mode=recommend 时必须传 people_count（出行人数）。");
                double budget = args.path("budget_per_person").asDouble(50);
                String scenario = args.path("scenario").asText("");
                JsonNode distNode = args.path("distance_km");
                double km = distNode.isNumber() ? distNode.asDouble() : 10.0;
                String city = args.path("city").asText("北京");
                return AgentToolResult.text(
                        didiRideService.recommendProduct(people, budget, scenario, km, city));
            } else {
                String city = args.path("city").asText("");
                if (city.isBlank()) return AgentToolResult.text("mode=estimate 时必须传 city 参数。");
                double km = args.path("distance_km").asDouble(-1);
                if (km <= 0) return AgentToolResult.text("mode=estimate 时 distance_km 必须 > 0（建议先用 plan_route mode=distance 测出距离）。");
                int min = args.path("duration_min").asInt(-1);
                int hour = args.path("hour_of_day").asInt(-1);
                int dow = args.path("day_of_week").asInt(-1);
                List<String> products = new ArrayList<>();
                JsonNode prods = args.path("products");
                if (prods.isArray()) {
                    for (JsonNode p : prods) products.add(p.asText());
                }
                return AgentToolResult.text(
                        didiRideService.estimatePrice(city, km, min, products, hour, dow));
            }
        } catch (Exception e) {
            return AgentToolResult.text("滴滴打车工具参数解析失败：" + e.getMessage());
        }
    }
}
