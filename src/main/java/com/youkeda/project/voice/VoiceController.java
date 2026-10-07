package com.youkeda.project.voice;

import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    @PostMapping("/asr")
    public VoiceAsrResponse transcribe(@RequestBody VoiceAsrRequest request) {
        if (request.audioBase64() == null || request.audioBase64().isBlank()) {
            throw new IllegalArgumentException("audioBase64 is required");
        }
        byte[] audioBytes = Base64.getDecoder().decode(request.audioBase64().trim());
        String text = voiceService.transcribe(audioBytes, request.mimeType(), request.language());
        return new VoiceAsrResponse(text);
    }

    @PostMapping("/tts")
    public TtsResponse synthesize(@RequestBody TtsRequest request) {
        SynthesizedAudio audio = voiceService.synthesize(request.text(), request.format());
        int durationMs = voiceService.estimateDurationMs(audio.bytes(), audio.format(), request.text());
        return new TtsResponse(
            Base64.getEncoder().encodeToString(audio.bytes()),
            audio.format(),
            audio.contentType(),
            audio.fileName(),
            audio.sampleRate(),
            durationMs);
    }

    @PostMapping("/tts/audio")
    public ResponseEntity<byte[]> synthesizeAudio(@RequestBody TtsRequest request) {
        SynthesizedAudio audio = voiceService.synthesize(request.text(), request.format());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(audio.contentType()))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + audio.fileName() + "\"")
            .body(audio.bytes());
    }
}
