package com.youkeda.project.llm;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WanxiangImageService {

    private static final Logger log = LoggerFactory.getLogger(WanxiangImageService.class);

    @Value(
        "${llm.image-api-url:https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis}")
    private String apiUrl;

    @Value("${llm.image-model:wanx2.1-t2i-turbo}")
    private String model;

    @Value("${llm.image-default-size:1024*1024}")
    private String defaultSize;

    @Value("${llm.image-timeout-seconds:180}")
    private long timeoutSeconds;

    @Value("${llm.image-poll-interval-ms:5000}")
    private long pollIntervalMs;

    @Value("${llm.image-prompt-extend:true}")
    private boolean promptExtendDefault = true;

    @Value("${llm.image-watermark:false}")
    private boolean watermarkDefault = false;

    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey = "";

    @PostConstruct
    void init() {
        this.apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            log.warn("DASHSCOPE_API_KEY is not configured; image generation is disabled");
        }
    }

    public ImageGenerationResponse generate(ImageGenerationRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        if (apiKey.isBlank()) {
            throw new LlmException("DASHSCOPE_API_KEY is not configured");
        }

        String size =
            request.size() == null || request.size().isBlank() ? defaultSize : request.size().trim();
        int imageCount = request.n() == null ? 1 : Math.max(1, Math.min(4, request.n()));
        String taskId =
            submitTask(
                request.prompt().trim(),
                request.negativePrompt(),
                size,
                imageCount,
                request.promptExtend() == null ? promptExtendDefault : request.promptExtend(),
                request.watermark() == null ? watermarkDefault : request.watermark(),
                request.seed());
        List<String> imageUrls = pollTask(taskId);
        return new ImageGenerationResponse(imageUrls, size);
    }

    public byte[] downloadImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("imageUrl is required");
        }
        try {
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(imageUrl.trim()))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new LlmException(
                    "Download generated image failed: HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new LlmException("Download generated image failed: empty response");
            }
            return body;
        } catch (IOException e) {
            throw new LlmException("Download generated image failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Download generated image interrupted", e);
        }
    }

    private String submitTask(
        String prompt,
        String negativePrompt,
        String size,
        int imageCount,
        boolean promptExtend,
        boolean watermark,
        Integer seed) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", model);

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", prompt);
            if (negativePrompt != null && !negativePrompt.isBlank()) {
                input.put("negative_prompt", negativePrompt.trim());
            }
            requestBody.put("input", input);

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("size", size);
            parameters.put("n", imageCount);
            parameters.put("prompt_extend", promptExtend);
            parameters.put("watermark", watermark);
            if (seed != null) {
                parameters.put("seed", seed);
            }
            requestBody.put("parameters", parameters);

            HttpRequest request =
                HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-Async", "enable")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody),
                            StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new LlmException(
                    "DashScope image API returned HTTP "
                        + response.statusCode()
                        + ": "
                        + responseError(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode output = root.path("output");
            String taskId = output.path("task_id").asText(null);
            String taskStatus = output.path("task_status").asText("");
            if (taskId == null || taskId.isBlank()) {
                throw new LlmException(
                    "DashScope image API returned no task id: " + response.body());
            }
            if ("FAILED".equals(taskStatus) || "CANCELED".equals(taskStatus)) {
                throw new LlmException(
                    "DashScope image task failed: " + taskError(output));
            }
            log.info(
                "Image task submitted: id={}, status={}, model={}, prompt=[{}]",
                taskId,
                taskStatus,
                model,
                prompt);
            return taskId;
        } catch (IOException e) {
            throw new LlmException("DashScope image API request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("DashScope image API request interrupted", e);
        }
    }

    private List<String> pollTask(String taskId) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                throw new LlmException(
                    "Image generation timed out after " + timeoutSeconds + " seconds");
            }
            try {
                HttpRequest request =
                    HttpRequest.newBuilder(URI.create(taskUrl(taskId)))
                        .timeout(Duration.ofSeconds(Math.min(Math.max(timeoutSeconds, 1), 60)))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .build();
                HttpResponse<String> response =
                    httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new LlmException(
                        "DashScope image task query returned HTTP "
                            + response.statusCode()
                            + ": "
                            + responseError(response.body()));
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode output = root.path("output");
                String taskStatus = output.path("task_status").asText("");
                if ("SUCCEEDED".equals(taskStatus)) {
                    List<String> imageUrls = extractImageUrls(output);
                    if (imageUrls.isEmpty()) {
                        throw new LlmException(
                            "Image task succeeded but returned no image URLs");
                    }
                    log.info(
                        "Image task succeeded: id={}, images={}",
                        taskId,
                        imageUrls.size());
                    return imageUrls;
                }
                if ("FAILED".equals(taskStatus) || "CANCELED".equals(taskStatus)) {
                    throw new LlmException(
                        "DashScope image task " + taskStatus.toLowerCase() + ": " + taskError(output));
                }
                if ("UNKNOWN".equals(taskStatus)) {
                    throw new LlmException("DashScope image task is unknown or expired");
                }
                log.debug("Image task pending: id={}, status={}", taskId, taskStatus);

                Thread.sleep(pollIntervalMs);
            } catch (IOException e) {
                throw new LlmException(
                    "DashScope image task query failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("DashScope image task query interrupted", e);
            }
        }
    }

    private List<String> extractImageUrls(JsonNode output) {
        List<String> imageUrls = new ArrayList<>();
        JsonNode results = output.path("results");
        if (results.isArray()) {
            for (JsonNode result : results) {
                String url = result.path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    imageUrls.add(url);
                }
            }
        }
        JsonNode choices = output.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("message").path("content");
                if (content.isArray()) {
                    for (JsonNode part : content) {
                        String image = part.path("image").asText(null);
                        if (image != null && !image.isBlank()) {
                            imageUrls.add(image);
                        }
                    }
                }
            }
        }
        return imageUrls;
    }

    private String taskUrl(String taskId) {
        int servicesIndex = apiUrl.indexOf("/services/");
        String base = servicesIndex > 0 ? apiUrl.substring(0, servicesIndex) : apiUrl;
        return base + "/tasks/" + taskId;
    }

    private String responseError(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String code = root.path("code").asText("");
            String message = root.path("message").asText("");
            if (!code.isBlank() || !message.isBlank()) {
                return code + " " + message;
            }
        } catch (IOException ignored) {
            // fall through and return the raw body
        }
        return body;
    }

    private String taskError(JsonNode output) {
        String code = output.path("code").asText("");
        String message = output.path("message").asText("");
        if (!code.isBlank() || !message.isBlank()) {
            return code + " " + message;
        }
        return "no error details provided";
    }

    private String resolveApiKey() {
        String envKey = System.getenv("DASHSCOPE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        for (String fileName : List.of(".env.local", ".env")) {
            Path file = Path.of(fileName);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("DASHSCOPE_API_KEY=")) {
                        return trimmed.substring("DASHSCOPE_API_KEY=".length()).trim();
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", fileName, e.getMessage());
            }
        }
        return "";
    }
}
