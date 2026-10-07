package com.youkeda.project.wechat;

import java.util.List;

public final class MediaContentFusion {

    private MediaContentFusion() {}

    public static String fuseText(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return "";
        }
        return texts.stream()
            .filter(text -> text != null && !text.isBlank())
            .map(String::trim)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    public static String buildVisionPrompt(String userText) {
        String text = userText == null ? "" : userText.trim();
        if (text.isBlank()) {
            return "请分析这张图片并给出简洁、准确的中文回答。";
        }
        return "用户同时发送了图片和以下文字，请结合图片内容回答用户的问题：\n" + text;
    }

    public static String buildGenerationPrompt(String userPrompt, String imageDescription) {
        String prompt = userPrompt == null || userPrompt.isBlank() ? "生成一张与图片风格相似的图片" : userPrompt.trim();
        String description = imageDescription == null || imageDescription.isBlank() ? "" : imageDescription.trim();
        if (description.isBlank()) {
            return prompt;
        }
        return "请参考原图描述生成图片：\n原图描述：" + description + "\n用户要求：" + prompt;
    }
}
