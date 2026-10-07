package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.llm.ImageGenerationRequest;
import com.youkeda.project.llm.ImageGenerationResponse;
import com.youkeda.project.llm.WanxiangImageService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ImageGenerationTool implements AgentTool {

    private final WanxiangImageService wanxiangImageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageGenerationTool(WanxiangImageService wanxiangImageService) {
        this.wanxiangImageService = wanxiangImageService;
    }

    @Override
    public String name() {
        return "generate_image";
    }

    @Override
    public String description() {
        return "根据用户的文字描述生成一张图片，返回图片访问地址。适合用户要求画图、生成图片或创作视觉内容时使用。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
            "prompt",
            Map.of("type", "string", "description", "用于生成图片的详细提示词"));
        properties.put(
            "negativePrompt",
            Map.of("type", "string", "description", "不希望出现在图片中的内容，可选"));
        properties.put(
            "size",
            Map.of(
                "type", "string",
                "enum", new String[] {"1024*1024", "1104*1472", "1472*1104", "960*1696", "1696*960"},
                "description", "图片尺寸，默认 1024*1024"));
        properties.put(
            "n",
            Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 4,
                "description", "生成图片数量，默认 1"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("prompt"));
        return parameters;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String prompt = args.path("prompt").asText("");
            if (prompt.isBlank()) {
                return AgentToolResult.text("缺少图片生成提示词 prompt。");
            }
            ImageGenerationRequest request =
                new ImageGenerationRequest(
                    prompt,
                    args.path("negativePrompt").asText(null),
                    args.path("size").asText("1024*1024"),
                    args.path("n").asInt(1),
                    null,
                    null,
                    null);
            ImageGenerationResponse response = wanxiangImageService.generate(request);
            if (response.imageUrls() == null || response.imageUrls().isEmpty()) {
                return AgentToolResult.text("图片生成完成，但没有返回图片地址。");
            }
            String imageUrl = response.imageUrls().get(0);
            return AgentToolResult.image("图片生成成功：" + imageUrl, imageUrl);
        } catch (Exception e) {
            return AgentToolResult.text("图片生成失败：" + e.getMessage());
        }
    }
}
