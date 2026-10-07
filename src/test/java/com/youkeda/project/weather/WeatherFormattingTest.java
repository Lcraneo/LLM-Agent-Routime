package com.youkeda.project.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WeatherFormattingTest {

    @Test
    void formatsCurrentWeather() {
        WeatherService weatherService = new WeatherService();
        WeatherNow now =
            new WeatherNow(
                new WeatherLocation("WX4FBXXFKE4F", "Beijing", "China", "Beijing, Beijing",
                    "Asia/Shanghai", "+08:00"),
                "多云",
                "4",
                "26",
                "27",
                "65",
                "东南",
                "3",
                "1013",
                "16",
                "2026-08-18T10:00:00+08:00");

        String formatted = weatherService.formatNow(now);
        assertTrue(formatted.contains("Beijing"));
        assertTrue(formatted.contains("多云"));
        assertTrue(formatted.contains("26"));
        assertTrue(formatted.contains("65%"));
    }

    @Test
    void formatsDailyForecast() {
        WeatherService weatherService = new WeatherService();
        WeatherForecast forecast =
            new WeatherForecast(
                new WeatherLocation("WX4FBXXFKE4F", "Beijing", "China", "Beijing, Beijing",
                    "Asia/Shanghai", "+08:00"),
                "2026-08-18T10:00:00+08:00",
                List.of(
                    new WeatherDaily("2026-08-18", "多云", "4", "晴", "1", "32", "25", "60",
                        "东南", "3"),
                    new WeatherDaily("2026-08-19", "小雨", "7", "多云", "4", "30", "24", "70",
                        "南", "2")));

        String formatted = weatherService.formatForecast(forecast);
        assertTrue(formatted.contains("未来2天"));
        assertTrue(formatted.contains("2026-08-18"));
        assertTrue(formatted.contains("32"));
        assertTrue(formatted.contains("25"));
    }
}
