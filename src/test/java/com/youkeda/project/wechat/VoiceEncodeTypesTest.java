package com.youkeda.project.wechat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoiceEncodeTypesTest {

    @Test
    void mapsCommonVoiceFormatsToWeChatEncodeTypes() {
        assertEquals(7, VoiceEncodeTypes.forFormat("mp3"));
        assertEquals(1, VoiceEncodeTypes.forFormat("wav"));
        assertEquals(1, VoiceEncodeTypes.forFormat("pcm"));
        assertEquals(8, VoiceEncodeTypes.forFormat("opus"));
        assertEquals(4, VoiceEncodeTypes.forFormat("silk"));
        assertEquals(4, VoiceEncodeTypes.forFormat(null));
    }
}
