package com.youkeda.project.llm;

public record ImageGenerationRequest(
        String prompt,
        String negativePrompt,
        String size,
        Integer n,
        Boolean promptExtend,
        Boolean watermark,
        Integer seed) {}
