package com.medicity.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads handwriting with Claude, through the Messages API.
 *
 * <p>Used only when {@code medicity.ai.anthropic-api-key} is set (from the
 * {@code ANTHROPIC_API_KEY} environment variable). Without it,
 * {@link #available()} is false and the doctor types the medicines, with the
 * photo still attached; nothing else changes.
 *
 * <p>The model is asked for JSON only, and its answer is parsed as data. Text
 * written on the slip ("ignore previous instructions") can at worst produce a
 * wrong draft, which the doctor sees and corrects before anything is issued.
 * The instructions and the checks on the answer are shared with the Gemini
 * reader ({@link ReadingParser}).
 */
@Component
@Slf4j
public class ClaudePrescriptionReader implements PrescriptionReader {

    /** Kept as a name for the tests and readers of this class; the text is shared. */
    static final String SYSTEM = ReadingParser.INSTRUCTIONS;

    private final RestClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;

    /**
     * {@code @Autowired} because there are two constructors: without it Spring
     * cannot choose, and the application does not start (entry 20 of the log).
     */
    @Autowired
    public ClaudePrescriptionReader(ObjectMapper json,
                                    @Value("${medicity.ai.anthropic-api-key:}") String apiKey,
                                    @Value("${medicity.ai.model:claude-sonnet-5}") String model,
                                    @Value("${medicity.ai.base-url:https://api.anthropic.com}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(45));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    /** For tests: a client pointed at a mock server. */
    ClaudePrescriptionReader(RestClient http, ObjectMapper json, String apiKey, String model) {
        this.http = http;
        this.json = json;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty();
    }

    @Override
    public Reading read(byte[] image, String contentType) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 2000,
                "system", SYSTEM,
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image", "source", Map.of(
                                "type", "base64", "media_type", contentType,
                                "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("type", "text", "text", "Read this prescription.")))));
        JsonNode response;
        try {
            response = http.post().uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            if (e instanceof org.springframework.web.client.RestClientResponseException r) {
                log.warn("Claude answered {}: {}", r.getStatusCode().value(),
                        r.getResponseBodyAsString().length() > 300 ? r.getResponseBodyAsString().substring(0, 300)
                                : r.getResponseBodyAsString());
            }
            throw ReadingParser.callFailed(e, "Claude", model);
        }
        return parse(response);
    }

    Reading parse(JsonNode response) {
        StringBuilder text = new StringBuilder();
        if (response != null) {
            for (JsonNode block : response.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        return ReadingParser.fromText(text, json);
    }
}
