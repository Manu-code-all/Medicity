package com.medicity.security;

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
import java.util.List;
import java.util.Map;

/**
 * Emails sign-in codes through Resend's HTTP API.
 *
 * <p>An HTTP API rather than SMTP: several hosts, Railway's smaller plans
 * among them, block outbound SMTP ports, which would work on a laptop and then
 * fail once deployed. Used only when both {@code RESEND_API_KEY} and
 * {@code RESEND_FROM} are set; until then {@link #available()} is false and
 * only the demo accounts can sign in with a code (shown on screen).
 *
 * <p>{@code RESEND_FROM} must belong to a domain verified in Resend. Resend's
 * own {@code onboarding@resend.dev} works without one, but only delivers to
 * the address the Resend account was created with.
 */
@Component
public class ResendEmailOtpSender implements EmailOtpSender {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String apiKey;
    private final String from;

    @Autowired
    public ResendEmailOtpSender(@Value("${medicity.email.resend.api-key:}") String apiKey,
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
    ResendEmailOtpSender(RestClient http, String apiKey, String from) {
        this.http = http;
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty() && !from.isEmpty();
    }

    /** Our code, our expiry: Resend only delivers it. Verification never leaves this server. */
    @Override
    public void send(String email, String code) {
        JsonNode reply;
        try {
            reply = http.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(email, code))
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

    /**
     * A String, not a Map: the client then knows the length up front and sends
     * a Content-Length, where a Map is streamed with chunked transfer encoding.
     * Both are valid HTTP, but a fixed length is the form every server and
     * proxy in front of a third-party API is certain to handle.
     */
    private String requestBody(String email, String code) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "from", from,
                    "to", List.of(email),
                    "subject", "Your Medicity sign-in code",
                    "text", text(code),
                    "html", html(code)));
        } catch (JsonProcessingException e) {
            throw new SendFailed("Could not build the request", e);
        }
    }

    /** The code is six digits, so nothing here needs escaping. */
    static String text(String code) {
        return """
                Your Medicity sign-in code is %s

                It works for 5 minutes, and once. If you did not ask for it, you can ignore this email: nobody can sign in without the code.
                """.formatted(code);
    }

    static String html(String code) {
        return """
                <div style="font-family:system-ui,sans-serif;max-width:420px">
                <p>Your Medicity sign-in code is</p>
                <p style="font-size:32px;letter-spacing:6px;font-weight:700;margin:8px 0">%s</p>
                <p>It works for 5 minutes, and once. If you did not ask for it, you can ignore this email: nobody can sign in without the code.</p>
                </div>
                """.formatted(code);
    }
}
