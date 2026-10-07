package com.youkeda.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.youkeda.project.weather.WeatherService;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeatherToolTest {

    @Test
    void queriesCurrentWeatherFromJsonArguments() {
        WeatherService weatherService = mock(WeatherService.class);
        when(weatherService.isConfigured()).thenReturn(true);
        when(weatherService.describeNow("上海")).thenReturn("上海 多云 26℃");

        WeatherTool tool = new WeatherTool(weatherService);
        AgentToolResult result = tool.execute("{\"location\":\"上海\",\"type\":\"now\"}");

        assertEquals("上海 多云 26℃", result.content());
        verify(weatherService).describeNow("上海");
    }

    @Test
    void queriesDailyForecastAndDefinesValidSchema() {
        WeatherService weatherService = mock(WeatherService.class);
        when(weatherService.isConfigured()).thenReturn(true);
        when(weatherService.describeForecast("beijing", 3)).thenReturn("beijing 未来3天");

        WeatherTool tool = new WeatherTool(weatherService);
        AgentToolResult result = tool.execute("{\"location\":\"beijing\",\"type\":\"daily\"}");

        assertEquals("beijing 未来3天", result.content());
        verify(weatherService).describeForecast("beijing", 3);

        Object required = tool.parameters().get("required");
        assertTrue(required instanceof List<?>);
        assertTrue(((List<?>) required).contains("location"));
    }

    @Test
    void reportsMissingApiKey() {
        WeatherService weatherService = mock(WeatherService.class);
        when(weatherService.isConfigured()).thenReturn(false);

        WeatherTool tool = new WeatherTool(weatherService);
        AgentToolResult result = tool.execute("{\"location\":\"上海\"}");

        assertTrue(result.content().contains("尚未配置"));
    }
}
