package com.youkeda.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.youkeda.project.llm.ImageGenerationRequest;
import com.youkeda.project.llm.ImageGenerationResponse;
import com.youkeda.project.llm.WanxiangImageService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageGenerationToolTest {

    @Test
    void generatesImageAndReturnsUrl() {
        WanxiangImageService imageService = mock(WanxiangImageService.class);
        when(imageService.generate(any(ImageGenerationRequest.class)))
            .thenReturn(
                new ImageGenerationResponse(
                    List.of("https://example.com/generated.png"), "1024*1024"));

        ImageGenerationTool tool = new ImageGenerationTool(imageService);
        AgentToolResult result =
            tool.execute("{\"prompt\":\"一只橘猫\",\"size\":\"1024*1024\"}");

        assertEquals("https://example.com/generated.png", result.imageUrl());
        assertTrue(result.content().contains("生成成功"));
        verify(imageService).generate(any(ImageGenerationRequest.class));
    }

    @Test
    void requiresPromptInSchemaAndExecution() {
        ImageGenerationTool tool =
            new ImageGenerationTool(mock(WanxiangImageService.class));

        Object required = tool.parameters().get("required");
        assertTrue(required instanceof List<?>);
        assertTrue(((List<?>) required).contains("prompt"));

        AgentToolResult result = tool.execute("{}");
        assertTrue(result.content().contains("缺少"));
    }
}
