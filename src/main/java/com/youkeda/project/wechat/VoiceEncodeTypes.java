package com.youkeda.project.wechat;

import java.util.Locale;

public final class VoiceEncodeTypes {

    private VoiceEncodeTypes() {}

    public static int forFormat(String format) {
        String normalized =
            format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mp3" -> 7;
            case "wav", "pcm" -> 1;
            case "opus" -> 8;
            // 微信语音实际使用的编码标记（服务器下发的语音即此值，数据为 SILK Tencent 变体）
            case "silk" -> 4;
            default -> 4;
        };
    }
}
