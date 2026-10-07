package com.youkeda.project.llm;

import java.util.List;

public record ImageGenerationResponse(List<String> imageUrls, String size) {}
