package com.youkeda.project.weather;

public record WeatherNow(
    WeatherLocation location,
    String text,
    String code,
    String temperature,
    String feelsLike,
    String humidity,
    String windDirection,
    String windScale,
    String pressure,
    String visibility,
    String lastUpdate) {}
