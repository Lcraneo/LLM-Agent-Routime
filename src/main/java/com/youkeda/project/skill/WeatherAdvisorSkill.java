package com.youkeda.project.skill;

import com.youkeda.project.llm.QwenChatService;
import com.youkeda.project.weather.WeatherService;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


@Component
public class WeatherAdvisorSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(WeatherAdvisorSkill.class);


    private static final List<String> CITIES =
        List.of(
            "乌鲁木齐", "石家庄", "呼和浩特", "哈尔滨",
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆",
            "天津", "苏州", "长沙", "郑州", "青岛", "厦门", "昆明", "大连", "沈阳", "济南",
            "合肥", "福州", "南昌", "贵阳", "兰州", "西宁", "南宁", "海口", "三亚", "长春",
            "无锡", "宁波", "温州", "佛山", "东莞", "珠海", "中山", "惠州", "常州", "绍兴",
            "嘉兴", "泉州", "洛阳", "烟台", "唐山", "拉萨", "银川");

    private static final List<String> FORECAST_SIGNALS =
        List.of("明天", "后天", "未来", "预报", "几天", "三天", "一周", "这几天");

    private final WeatherService weatherService;
    private final QwenChatService qwenChatService;

    public WeatherAdvisorSkill(WeatherService weatherService, QwenChatService qwenChatService) {
        this.weatherService = weatherService;
        this.qwenChatService = qwenChatService;
    }

    @Override
    public String name() {
        return "weather_advisor";
    }

    @Override
    public List<String> keywords() {
        return List.of(
            "穿衣建议",
            "穿衣指数",
            "穿什么衣服",
            "适合穿什么",
            "要不要带伞",
            "需要带伞",
            "带伞吗",
            "带伞么",
            "出行建议",
            "出门建议",
            "天气建议",
            "天气顾问",
            "适不适合出门",
            "适合出门吗");
    }

    @Override
    public String description() {
        return "天气顾问：根据城市实时天气或预报，由大模型给出穿衣、带伞、出行建议。";
    }

    @Override
    public String execute(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "请告诉我你想查询哪个城市的天气，例如：上海今天适合穿什么";
        }
        String location = extractLocation(userInput);
        if (location == null) {
            return "请告诉我你想查询哪个城市的天气，例如：\"北京明天适合穿什么\"、\"上海要不要带伞\"。";
        }
        if (!weatherService.isConfigured()) {
            return "天气服务尚未配置 SENIVERSE API Key，暂时无法给出天气建议。";
        }

        // 参数提取：默认查实时天气，包含未来信号时查 3 天预报
        boolean forecast = FORECAST_SIGNALS.stream().anyMatch(userInput::contains);
        String weatherText;
        if (forecast) {
            weatherText = weatherService.describeForecast(location, 3);
        } else {
            weatherText = weatherService.describeNow(location);
        }
        log.info(
            "WeatherAdvisorSkill location={} forecast={} weatherChars={}",
            location,
            forecast,
            weatherText == null ? 0 : weatherText.length());

        // LLM 分析：把天气数据交给大模型生成建议
        String prompt =
            "你是贴心的天气顾问。请根据下面的天气数据，用中文给出简洁实用的建议，"
                + "包括：是否需要带伞、推荐穿什么衣服、出行注意事项。分点输出。\n"
                + "=== 天气数据 ===\n"
                + weatherText.trim()
                + "\n=== 用户原话 ===\n"
                + userInput.trim();
        try {
            String reply = qwenChatService.chat(prompt);
            if (reply != null && !reply.isBlank()) {
                return reply;
            }
        } catch (RuntimeException e) {
            log.warn("WeatherAdvisorSkill LLM analysis failed: {}", e.getMessage());
        }
        // LLM 失败时兜底返回原始天气数据
        return location + " 的天气数据如下：\n" + weatherText.trim();
    }


    static String extractLocation(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return CITIES.stream()
            .filter(text::contains)
            .min(Comparator.comparingInt(text::indexOf))
            .orElse(null);
    }
}
