package com.youkeda.project.voice;

public record VoiceAsrRequest(String audioBase64, String mimeType, String language) {}
