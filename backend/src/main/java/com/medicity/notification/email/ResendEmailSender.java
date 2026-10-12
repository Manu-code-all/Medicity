package com.medicity.notification.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends notification emails through Resend's HTTP API, with the same
 * {@code RESEND_API_KEY} and {@code RESEND_FROM} as the sign-in codes. Unset,
 * {@link #available()} is false and the whole feature is a no-op.
 *
 * <p>Short timeouts (5 s to connect, 10 s to read): the caller is the outbox
 * relay, which holds a database transaction while it waits.
 */
@Component
public class ResendEmailSender implements EmailSender {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String apiKey;
    private final String from;

    @Autowired
    public ResendEmailSender(@Value("${medicity.email.resend.api-key:}") String apiKey,
                             @Value("${medicity.email.resend.from:}") String from,
                             @Value("${medicity.email.resend.base-url:https://api.resend.com}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
    }

    /** For tests: a client pointed at a mock server. */
    ResendEmailSender(RestClient http, String apiKey, String from) {
        this.http = http;
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty() && !from.isEmpty();
    }

    @Override
    public void send(Message message) {
        JsonNode reply;
        try {
            reply = http.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(message))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            // Includes 4xx and 5xx answers; the status is in the exception message.
            throw new SendFailed("Resend refused or could not be reached: " + e.getMessage(), e);
        }
        if (reply == null || reply.path("id").asText("").isEmpty()) {
            throw new SendFailed("Resend gave no message id", null);
        }
    }

    /** A String, not a Map, so a Content-Length is sent (see ResendEmailOtpSender). */
    String requestBody(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(message.to()));
        body.put("subject", message.subject());
        body.put("text", message.text());
        body.put("html", message.html());
        if (!message.headers().isEmpty()) {
            body.put("headers", message.headers());
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new SendFailed("Could not build the request", e);
        }
    }
}
