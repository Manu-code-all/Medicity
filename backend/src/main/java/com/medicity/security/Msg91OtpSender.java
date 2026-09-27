package com.medicity.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Sends sign-in codes by SMS through MSG91, an Indian provider that handles
 * the DLT template registration Indian SMS requires.
 *
 * <p>Used only when both {@code MSG91_AUTH_KEY} and {@code MSG91_OTP_TEMPLATE_ID}
 * are set. Until then {@link #available()} is false, and only the demo
 * accounts can sign in with a code (shown on screen); everyone else signs in
 * with email and password.
 */
@Component
public class Msg91OtpSender implements OtpSender {

    private final RestClient http;
    private final String authKey;
    private final String templateId;

    @Autowired
    public Msg91OtpSender(@Value("${medicity.sms.msg91.auth-key:}") String authKey,
                          @Value("${medicity.sms.msg91.otp-template-id:}") String templateId,
                          @Value("${medicity.sms.msg91.base-url:https://control.msg91.com}") String baseUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts).build();
        this.authKey = authKey == null ? "" : authKey.trim();
        this.templateId = templateId == null ? "" : templateId.trim();
    }

    /** For tests: a client pointed at a mock server. */
    Msg91OtpSender(RestClient http, String authKey, String templateId) {
        this.http = http;
        this.authKey = authKey;
        this.templateId = templateId;
    }

    @Override
    public boolean available() {
        return !authKey.isEmpty() && !templateId.isEmpty();
    }

    /** Our code, our expiry: MSG91 only delivers it. Verification never leaves this server. */
    @Override
    public void send(String phone, String code) {
        JsonNode reply;
        try {
            reply = http.post()
                    .uri(uri -> uri.path("/api/v5/otp")
                            .queryParam("template_id", templateId)
                            .queryParam("mobile", phone.substring(1))   // 91XXXXXXXXXX
                            .queryParam("otp", code)
                            .build())
                    .header("authkey", authKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new SendFailed("MSG91 could not be reached", e);
        }
        if (reply == null || !"success".equals(reply.path("type").asText())) {
            throw new SendFailed("MSG91 refused the message: " + (reply == null ? "no reply" : reply.path("message").asText()), null);
        }
    }
}
