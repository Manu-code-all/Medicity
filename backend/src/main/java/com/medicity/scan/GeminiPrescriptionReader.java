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
 * Reads handwriting with Google's Gemini, through the {@code generateContent}
 * API. Gemini has a free tier, so the feature can run at no cost.
 *
 * <p>Used only when {@code GEMINI_API_KEY} is set. Free-tier requests may be
 * used by Google to improve its products, so a deployment reading real
 * patients' prescriptions should use a paid key; the demo reads demo slips.
 * Same instructions and same checks on the answer as the Claude reader
 * ({@link ReadingParser}).
 */
@Component
@Slf4j
public class GeminiPrescriptionReader implements PrescriptionReader {

    private final RestClient http;
    private final ObjectMapper json;
    private final String apiKey;
    private final String model;

    @Autowired
    public GeminiPrescriptionReader(ObjectMapper json,
                                    @Value("${medicity.ai.gemini-api-key:}") String apiKey,
                                    @Value("${medicity.ai.gemini-model:gemini-3.8-flash}") String model,
                                    @Value("${medicity.ai.gemini-base-url:https://generativelanguage.googleapis.com}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(45));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    /** For tests: a client pointed at a mock server. */
    GeminiPrescriptionReader(RestClient http, ObjectMapper json, String apiKey, String model) {
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
                "systemInstruction", Map.of("parts", List.of(Map.of("text", ReadingParser.INSTRUCTIONS))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("inlineData", Map.of(
                                "mimeType", contentType,
                                "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("text", "Read this prescription.")))),
                // Asks for JSON directly; the parser still tolerates a code fence.
                "generationConfig", Map.of("responseMimeType", "application/json", "maxOutputTokens", 2000));
        JsonNode response;
        try {
            response = http.post().uri("/v1beta/models/{model}:generateContent", model)
                    // A header, not ?key=: query strings end up in logs.
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            if (e instanceof org.springframework.web.client.RestClientResponseException r) {
                log.warn("Gemini answered {}: {}", r.getStatusCode().value(), abbreviate(r.getResponseBodyAsString()));
            }
            throw ReadingParser.callFailed(e, "Gemini", model);
        }
        return parse(response);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    Reading parse(JsonNode response) {
        StringBuilder text = new StringBuilder();
        if (response != null) {
            for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
        }
        return ReadingParser.fromText(text, json);
    }
}
