package com.youkeda.project.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class VoiceServiceTest {

    @Test
    void estimatesWavDurationFromHeader() {
        byte[] wav = createWav(16000, 2, 32000);
        VoiceService voiceService = new VoiceService();
        assertEquals(500, voiceService.estimateDurationMs(wav, "wav", "hello"));
    }

    @Test
    void rejectsEmptyAudio() {
        VoiceService voiceService = new VoiceService();
        assertThrows(IllegalArgumentException.class, () -> voiceService.transcribe(null, "audio/wav"));
        assertThrows(
            IllegalArgumentException.class,
            () -> voiceService.transcribe(new byte[0], "audio/wav"));
    }

    private byte[] createWav(int sampleRate, int channels, int dataSize) {
        int byteRate = sampleRate * channels * 2;
        byte[] wav = new byte[44 + dataSize];
        writeAscii(wav, 0, "RIFF");
        writeInt(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeInt(wav, 16, 16);
        writeShort(wav, 20, 1);
        writeShort(wav, 22, channels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, channels * 2);
        writeShort(wav, 34, 16);
        writeAscii(wav, 36, "data");
        writeInt(wav, 40, dataSize);
        return wav;
    }

    private void writeAscii(byte[] bytes, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            bytes[offset + i] = (byte) text.charAt(i);
        }
    }

    private void writeInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
    }

    private void writeShort(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value);
    }
}
