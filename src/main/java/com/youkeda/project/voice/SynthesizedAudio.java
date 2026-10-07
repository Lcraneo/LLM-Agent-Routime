package com.youkeda.project.voice;

public record SynthesizedAudio(
    byte[] bytes,
    String format,
    String contentType,
    String fileName,
    int sampleRate) {}
