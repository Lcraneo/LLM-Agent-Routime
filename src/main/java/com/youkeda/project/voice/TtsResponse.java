package com.youkeda.project.voice;

public record TtsResponse(
    String audioBase64,
    String format,
    String contentType,
    String fileName,
    int sampleRate,
    int durationMs) {}
