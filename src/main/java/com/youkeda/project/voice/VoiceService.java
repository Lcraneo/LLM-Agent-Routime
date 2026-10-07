package com.youkeda.project.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    private static final Set<String> SUPPORTED_TTS_FORMATS = Set.of("wav", "mp3", "pcm", "opus");

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String asrBaseUrl;

    @Value("${llm.asr-model:qwen3-asr-flash}")
    private String asrModel;

    @Value("${tts.api-url:https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer}")
    private String ttsApiUrl;

    @Value("${tts.model:qwen-audio-3.0-tts-flash}")
    private String ttsModel;

    @Value("${tts.voice:longanhuan_v3.6}")
    private String ttsVoice;

    @Value("${tts.format:wav}")
    private String ttsDefaultFormat;

    @Value("${tts.sample-rate:24000}")
    private int ttsSampleRate;

    @Value("${tts.timeout-seconds:60}")
    private long ttsTimeoutSeconds;

    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey = "";

    @PostConstruct
    void init() {
        this.apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY is not configured; voice ASR/TTS is disabled");
        }
    }

    public String transcribe(byte[] audioBytes, String mimeType) {
        return transcribe(audioBytes, mimeType, null);
    }

    public String transcribe(byte[] audioBytes, String mimeType, String language) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio is required");
        }
        ensureApiKey();

        String normalizedMime = normalizeMimeType(mimeType, audioBytes);
        String dataUrl =
            "data:"
                + normalizedMime
                + ";base64,"
                + Base64.getEncoder().encodeToString(audioBytes);

        try {
            Map<String, Object> inputAudio = new LinkedHashMap<>();
            inputAudio.put("data", dataUrl);

            Map<String, Object> audioPart = new LinkedHashMap<>();
            audioPart.put("type", "input_audio");
            audioPart.put("input_audio", inputAudio);

            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", List.of(audioPart));

            Map<String, Object> asrOptions = new LinkedHashMap<>();
            asrOptions.put("enable_itn", true);
            if (language != null && !language.isBlank()) {
                asrOptions.put("language", language.trim());
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", asrModel);
            requestBody.put("messages", List.of(userMessage));
            requestBody.put("stream", false);
            requestBody.put("asr_options", asrOptions);

            HttpRequest request =
                HttpRequest.newBuilder(URI.create(asrBaseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(ttsTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new VoiceException(
                    "ASR API returned HTTP "
                        + response.statusCode()
                        + ": "
                        + errorMessage(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text =
                root.path("choices").path(0).path("message").path("content").asText(null);
            if (text == null || text.isBlank()) {
                throw new VoiceException("ASR API returned an empty transcription");
            }
            return text.trim();
        } catch (IOException e) {
            throw new VoiceException("ASR API request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("ASR API request interrupted", e);
        }
    }

    public SynthesizedAudio synthesize(String text, String format) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        ensureApiKey();
        String normalizedFormat = normalizeFormat(format);

        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("text", text.trim());
            input.put("voice", ttsVoice);

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("format", normalizedFormat);
            parameters.put("sample_rate", ttsSampleRate);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", ttsModel);
            requestBody.put("input", input);
            requestBody.put("parameters", parameters);

            HttpRequest request =
                HttpRequest.newBuilder(URI.create(ttsApiUrl))
                    .timeout(Duration.ofSeconds(ttsTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn(
                    "TTS API request body: {}",
                    objectMapper.writeValueAsString(requestBody));
                throw new VoiceException(
                    "TTS API returned HTTP "
                        + response.statusCode()
                        + ": "
                        + errorMessage(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode audio = root.path("output").path("audio");
            byte[] audioBytes;
            String data = audio.path("data").asText("");
            if (data != null && !data.isBlank()) {
                audioBytes = Base64.getDecoder().decode(data);
            } else {
                String url = audio.path("url").asText("");
                if (url == null || url.isBlank()) {
                    throw new VoiceException("TTS API returned no audio URL or data");
                }
                audioBytes = download(url);
            }
            if (audioBytes == null || audioBytes.length == 0) {
                throw new VoiceException("TTS API returned an empty audio file");
            }

            return new SynthesizedAudio(
                audioBytes,
                normalizedFormat,
                contentTypeFor(normalizedFormat),
                "reply." + normalizedFormat,
                ttsSampleRate);
        } catch (IOException e) {
            throw new VoiceException("TTS API request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceException("TTS API request interrupted", e);
        }
    }

    public int estimateDurationMs(byte[] audioBytes, String format, String text) {
        if (audioBytes != null && "wav".equals(format) && audioBytes.length >= 44) {
            int byteRate =
                (audioBytes[28] & 0xFF)
                    | ((audioBytes[29] & 0xFF) << 8)
                    | ((audioBytes[30] & 0xFF) << 16)
                    | ((audioBytes[31] & 0xFF) << 24);
            int dataSize = findWavDataSize(audioBytes);
            if (byteRate > 0 && dataSize > 0) {
                return Math.max(1, dataSize * 1000 / byteRate);
            }
        }
        if (text != null && !text.isBlank()) {
            return Math.max(1, text.length() * 180);
        }
        return 1000;
    }

    private int findWavDataSize(byte[] bytes) {
        int position = 12;
        while (position + 8 <= bytes.length) {
            String chunkId =
                String.valueOf((char) bytes[position])
                    + (char) bytes[position + 1]
                    + (char) bytes[position + 2]
                    + (char) bytes[position + 3];
            int chunkSize =
                (bytes[position + 4] & 0xFF)
                    | ((bytes[position + 5] & 0xFF) << 8)
                    | ((bytes[position + 6] & 0xFF) << 16)
                    | ((bytes[position + 7] & 0xFF) << 24);
            if ("data".equals(chunkId)) {
                return Math.min(chunkSize, Math.max(0, bytes.length - position - 8));
            }
            if (chunkSize <= 0) {
                break;
            }
            position += 8 + chunkSize + (chunkSize % 2);
        }
        return 0;
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<byte[]> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new VoiceException(
                "Download synthesized audio failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String normalizeFormat(String format) {
        String normalized =
            format == null || format.isBlank() ? ttsDefaultFormat : format.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TTS_FORMATS.contains(normalized)) {
            throw new IllegalArgumentException(
                "Unsupported audio format: "
                    + format
                    + ". Supported formats: wav, mp3, pcm, opus");
        }
        return normalized;
    }

    private String normalizeMimeType(String mimeType, byte[] audioBytes) {
        if (mimeType != null && !mimeType.isBlank()) {
            String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("audio/")) {
                return normalized;
            }
        }
        return detectMimeType(audioBytes);
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length >= 12
            && bytes[0] == 'R'
            && bytes[1] == 'I'
            && bytes[2] == 'F'
            && bytes[3] == 'F'
            && bytes[8] == 'W'
            && bytes[9] == 'A'
            && bytes[10] == 'V'
            && bytes[11] == 'E') {
            return "audio/wav";
        }
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xFB) {
            return "audio/mpeg";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if (bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') {
            return "audio/ogg";
        }
        if (bytes.length >= 4
            && bytes[0] == 0x1A
            && bytes[1] == 0x45
            && bytes[2] == 0xDF
            && bytes[3] == 0xA3) {
            return "audio/webm";
        }
        if (bytes.length >= 5
            && bytes[0] == '#'
            && bytes[1] == '!'
            && bytes[2] == 'A'
            && bytes[3] == 'M'
            && bytes[4] == 'R') {
            return "audio/amr";
        }
        if (bytes.length >= 6
            && bytes[0] == '#'
            && bytes[1] == '!'
            && bytes[2] == 'S'
            && bytes[3] == 'I'
            && bytes[4] == 'L'
            && bytes[5] == 'K') {
            return "audio/silk";
        }
        return "audio/wav";
    }

    private String contentTypeFor(String format) {
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "pcm" -> "audio/x-pcm";
            case "opus" -> "audio/opus";
            default -> "audio/wav";
        };
    }

    private String errorMessage(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("message").asText("");
            String code = root.path("code").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return (code.isBlank() ? "" : code + " ") + message;
            }
        } catch (IOException ignored) {
            // fall through and return raw body
        }
        return body;
    }

    private void ensureApiKey() {
        this.apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            throw new VoiceException("DASHSCOPE_API_KEY is not configured");
        }
    }

    private String resolveApiKey() {
        String envKey = System.getenv("DASHSCOPE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        for (String fileName : List.of(".env.local", ".env")) {
            Path file = findEnvFile(fileName);
            if (file == null) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("DASHSCOPE_API_KEY=")) {
                        String value = trimmed.substring("DASHSCOPE_API_KEY=".length()).trim();
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                        return value;
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", fileName, e.getMessage());
            }
        }
        return "";
    }

    private Path findEnvFile(String fileName) {
        Path dir = Path.of("").toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }
}
