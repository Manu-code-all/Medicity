package com.medicity.notification.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Resend call for notification emails, against a mock server. */
@DisplayName("Emailing notifications through Resend")
class ResendEmailSenderTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://resend.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ResendEmailSender sender =
            new ResendEmailSender(builder.build(), "re_test_key", "Medicity <hello@medicity.example>");

    private static EmailSender.Message message(Map<String, String> headers) {
        return new EmailSender.Message("meera@example.test", "Subject", "Plain", "<p>Html</p>", headers);
    }

    @Test
    @DisplayName("posts the message from the configured sender, with the key as a bearer token and the unsubscribe headers")
    void sends() {
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("Medicity <hello@medicity.example>"))
                .andExpect(jsonPath("$.to[0]").value("meera@example.test"))
                .andExpect(jsonPath("$.subject").value("Subject"))
                .andExpect(jsonPath("$.text").value("Plain"))
                .andExpect(jsonPath("$.html").value("<p>Html</p>"))
                .andExpect(jsonPath("$.headers.List-Unsubscribe").value("<https://api.test/u>"))
                .andRespond(withSuccess("{\"id\":\"4ef9a417\"}", MediaType.APPLICATION_JSON));

        sender.send(message(Map.of("List-Unsubscribe", "<https://api.test/u>")));
        server.verify();
    }

    @Test
    @DisplayName("without extra headers the request has no headers field")
    void noHeaders() {
        assertThat(sender.requestBody(message(Map.of()))).doesNotContain("headers");
    }

    @Test
    @DisplayName("a refusal, an outage or a reply without a message id is a SendFailed, not a crash")
    void failsCleanly() {
        server.expect(requestTo("https://resend.test/emails"))
                .andRespond(withStatus(FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"validation_error\",\"message\":\"domain not verified\",\"statusCode\":403}"));
        assertThatThrownBy(() -> sender.send(message(Map.of()))).isInstanceOf(EmailSender.SendFailed.class);

        server.reset();
        server.expect(requestTo("https://resend.test/emails")).andRespond(withStatus(INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> sender.send(message(Map.of()))).isInstanceOf(EmailSender.SendFailed.class);

        server.reset();
        server.expect(requestTo("https://resend.test/emails"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> sender.send(message(Map.of())))
                .isInstanceOf(EmailSender.SendFailed.class).hasMessageContaining("no message id");
    }

    @Test
    @DisplayName("is available only when both the key and the sender address are set")
    void availability() {
        assertThat(new ResendEmailSender("", "x@y.test", "https://resend.test").available()).isFalse();
        assertThat(new ResendEmailSender("re_key", "", "https://resend.test").available()).isFalse();
        assertThat(new ResendEmailSender(" re_key ", "x@y.test", "https://resend.test").available()).isTrue();
    }
}
