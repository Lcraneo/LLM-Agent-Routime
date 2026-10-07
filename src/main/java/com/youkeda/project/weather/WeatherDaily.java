package com.youkeda.project.weather;

public record WeatherDaily(
    String date,
    String textDay,
    String codeDay,
    String textNight,
    String codeNight,
    String high,
    String low,
    String humidity,
    String windDirection,
    String windScale) {}
