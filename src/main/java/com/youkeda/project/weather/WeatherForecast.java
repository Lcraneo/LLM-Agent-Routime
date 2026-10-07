package com.youkeda.project.weather;

import java.util.List;

public record WeatherForecast(
    WeatherLocation location,
    String lastUpdate,
    List<WeatherDaily> daily) {}
