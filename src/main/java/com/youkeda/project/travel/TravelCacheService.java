package com.youkeda.project.travel;

import com.youkeda.project.tool.AgentToolResult;
import com.youkeda.project.tool.WeatherTool;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定时预热缓存：每 2 小时为热门城市预取天气数据，
 * 使用户首次查询时直接命中 WeatherTool 内存缓存，零 API 调用延迟。
 */
@Service
public class TravelCacheService {

    private static final Logger log = LoggerFactory.getLogger(TravelCacheService.class);

    private static final List<String> HOT_CITIES =
            List.of("上海", "北京", "杭州", "成都", "西安");

    private final WeatherTool weatherTool;

    public TravelCacheService(WeatherTool weatherTool) {
        this.weatherTool = weatherTool;
    }

    /** 每 2 小时预热热门城市天气预报缓存。 */
    @Scheduled(fixedDelay = 2 * 60 * 60 * 1000, initialDelay = 30 * 1000)
    public void prefetchWeather() {
        for (String city : HOT_CITIES) {
            try {
                String args = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(Map.of("location", city, "type", "daily", "days", 3));
                AgentToolResult result = weatherTool.execute(args);
                log.info("天气预热 {} → {}字符", city,
                        result == null || result.content() == null ? 0 : result.content().length());
            } catch (Exception e) {
                log.warn("天气预热失败 {}: {}", city, e.getMessage());
            }
        }
        log.info("天气预热完成，{}个城市已缓存", HOT_CITIES.size());
    }
}
