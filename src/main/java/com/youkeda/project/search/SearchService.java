package com.youkeda.project.search;

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
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private String apiKey = "";

    @Value("${tavily.api.url:https://api.tavily.com/search}")
    private String apiUrl;

    @Value("${tavily.search.max-results:5}")
    private int maxResults;

    @Value("${tavily.search.timeout-seconds:15}")
    private long timeoutSeconds;

    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        this.apiKey = resolveApiKey();
        if (apiKey.isBlank()) {
            log.warn("TAVILY_API_KEY is not configured; web search is disabled");
        }
    }

    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Tavily API key is not configured; skip web search");
            return "";
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("api_key", apiKey.trim());
            requestBody.put("query", query.trim());
            requestBody.put("search_depth", "basic");
            requestBody.put("max_results", maxResults);
            requestBody.put("include_answer", false);

            HttpRequest request =
                HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Tavily search returned HTTP {}", response.statusCode());
                return "";
            }

            return extractSearchResults(response.body());
        } catch (IOException e) {
            log.warn("Tavily search request failed: {}", e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tavily search request interrupted");
            return "";
        }
    }

    private String extractSearchResults(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "";
            }

            List<String> snippets = new ArrayList<>();
            for (JsonNode result : results) {
                String title = result.path("title").asText("");
                String content = result.path("content").asText("");
                if (!content.isBlank()) {
                    snippets.add("- " + title + ": " + content);
                }
            }
            return String.join("\n", snippets);
        } catch (IOException e) {
            log.warn("Failed to parse Tavily search response: {}", e.getMessage());
            return "";
        }
    }

    private String resolveApiKey() {
        String envKey = System.getenv("TAVILY_API_KEY");
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
                    if (trimmed.startsWith("TAVILY_API_KEY=")) {
                        return trimmed.substring("TAVILY_API_KEY=".length()).trim();
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", fileName, e.getMessage());
            }
        }
        return "";
    }
}
