package com.youkeda.project.weather;

public record WeatherLocation(
    String id,
    String name,
    String country,
    String path,
    String timezone,
    String timezoneOffset) {}
