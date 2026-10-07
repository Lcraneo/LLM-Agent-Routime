package com.youkeda.project.weather;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/now")
    public WeatherNow now(@RequestParam String location) {
        return weatherService.getNow(location);
    }

    @GetMapping("/daily")
    public WeatherForecast daily(
        @RequestParam String location, @RequestParam(defaultValue = "3") int days) {
        return weatherService.getForecast(location, days);
    }

    @GetMapping("/now/formatted")
    public Map<String, String> formattedNow(@RequestParam String location) {
        return Map.of("text", weatherService.describeNow(location));
    }

    @GetMapping("/daily/formatted")
    public Map<String, String> formattedDaily(
        @RequestParam String location, @RequestParam(defaultValue = "3") int days) {
        return Map.of("text", weatherService.describeForecast(location, days));
    }
}
