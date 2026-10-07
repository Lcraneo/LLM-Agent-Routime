package com.youkeda.project.llm;

import java.util.Base64;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final QwenChatService qwenChatService;
    private final WanxiangImageService wanxiangImageService;

    public LlmController(
        QwenChatService qwenChatService, WanxiangImageService wanxiangImageService) {
        this.qwenChatService = qwenChatService;
        this.wanxiangImageService = wanxiangImageService;
    }

    @PostMapping("/chat")
    public LlmChatResponse chat(@RequestBody LlmChatRequest request) {
        return new LlmChatResponse(qwenChatService.chat(request.message()));
    }

    @PostMapping("/chat-with-image")
    public LlmChatResponse chatWithImage(@RequestBody LlmImageChatRequest request) {
        if (request.imageBase64() == null || request.imageBase64().isBlank()) {
            throw new IllegalArgumentException("imageBase64 is required");
        }
        byte[] imageBytes = Base64.getDecoder().decode(request.imageBase64());
        return new LlmChatResponse(qwenChatService.chatWithImage(imageBytes, request.prompt()));
    }

    @PostMapping("/generate-image")
    public ImageGenerationResponse generateImage(
        @RequestBody ImageGenerationRequest request) {
        return wanxiangImageService.generate(request);
    }
}
