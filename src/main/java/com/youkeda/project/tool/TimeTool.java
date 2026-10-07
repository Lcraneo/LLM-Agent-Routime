package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 实时时间查询工具（function-calling）。
 *
 * <p>返回服务器当前时间（默认 Asia/Shanghai），用户问"几点了/今天几号"时由 LLM 调用。
 */
@Component("agentTimeTool")
public class TimeTool implements AgentTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "get_time";
    }

    @Override
    public String description() {
        return "查询当前实时时间和日期。用户问'几点了'、'现在几点'、'今天几号'、'星期几'、"
            + "'现在什么时间'等问题时调用此工具，返回准确的当前时间。不要凭记忆回答时间。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
            "timezone",
            Map.of(
                "type", "string",
                "description",
                "可选，时区 IANA 名称，如 Asia/Shanghai、America/New_York、UTC。"
                    + "默认使用北京时间(Asia/Shanghai)"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        String zoneIdStr = "Asia/Shanghai";
        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                JsonNode args = objectMapper.readTree(argumentsJson);
                String tz = args.path("timezone").asText("");
                if (!tz.isBlank()) {
                    zoneIdStr = tz.trim();
                }
            } catch (Exception e) {
                // arguments 非法 JSON 时用默认时区
            }
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zoneIdStr);
        } catch (Exception e) {
            zoneId = ZoneId.of("Asia/Shanghai");
        }
        LocalDateTime now = LocalDateTime.now(zoneId);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分ss秒");
        DayOfWeek dow = now.getDayOfWeek();
        String weekday = weekdayChinese(dow);
        String content =
            "当前时间：" + now.format(fmt) + " " + weekday + "（时区：" + zoneId.getId() + "）";
        return AgentToolResult.text(content);
    }

    private static String weekdayChinese(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }
}
