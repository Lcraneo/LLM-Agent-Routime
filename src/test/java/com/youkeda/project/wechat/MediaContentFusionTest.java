package com.youkeda.project.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MediaContentFusionTest {

    @Test
    void fusesMultipleTextParts() {
        String fused = MediaContentFusion.fuseText(List.of(" 看下这张图 ", " 里面有猫吗 "));
        assertEquals("看下这张图\n里面有猫吗", fused);
    }

    @Test
    void buildsVisionPromptWithUserText() {
        String prompt = MediaContentFusion.buildVisionPrompt("这张图里有什么");
        assertTrue(prompt.contains("这张图里有什么"));
        assertTrue(prompt.contains("结合图片内容"));
    }

    @Test
    void buildsGenerationPromptWithImageDescription() {
        String prompt =
            MediaContentFusion.buildGenerationPrompt("生成类似图片", "原图是一片森林");
        assertTrue(prompt.contains("原图是一片森林"));
        assertTrue(prompt.contains("生成类似图片"));
    }
}
