package com.medicity.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/** The Resend call, against a mock server. */
@DisplayName("Emailing codes through Resend")
class ResendEmailOtpSenderTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://resend.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ResendEmailOtpSender sender =
            new ResendEmailOtpSender(builder.build(), "re_test_key", "Medicity <login@medicity.example>");

    @Test
    @DisplayName("posts our code to the address, from the configured sender, with the key as a bearer token")
    void sends() {
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("Medicity <login@medicity.example>"))
                .andExpect(jsonPath("$.to[0]").value("meera@example.test"))
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.subject").value("Your Medicity sign-in code"))
                .andExpect(jsonPath("$.text").value(containsString("482913")))
                .andExpect(jsonPath("$.html").value(containsString("482913")))
                .andRespond(withSuccess("{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}", MediaType.APPLICATION_JSON));

        sender.send("meera@example.test", "482913");
        server.verify();
    }

    @Test
    @DisplayName("the subject does not carry the code, so it does not show on a lock screen")
    void subjectHasNoCode() {
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(jsonPath("$.subject").value(org.hamcrest.Matchers.not(containsString("482913"))))
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        sender.send("meera@example.test", "482913");
        server.verify();
    }

    @Test
    @DisplayName("a refusal, an outage or a reply without a message id is a SendFailed, not a crash")
    void failsCleanly() {
        server.expect(requestTo("https://resend.test/emails"))
                .andRespond(withStatus(FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"validation_error\",\"message\":\"domain not verified\",\"statusCode\":403}"));
        assertThatThrownBy(() -> sender.send("meera@example.test", "482913"))
                .isInstanceOf(EmailOtpSender.SendFailed.class);

        server.reset();
        server.expect(requestTo("https://resend.test/emails")).andRespond(withStatus(INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> sender.send("meera@example.test", "482913"))
                .isInstanceOf(EmailOtpSender.SendFailed.class);

        server.reset();
        server.expect(requestTo("https://resend.test/emails"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> sender.send("meera@example.test", "482913"))
                .isInstanceOf(EmailOtpSender.SendFailed.class);
    }

    @Test
    @DisplayName("without both the key and the sender address it is not available")
    void availability() {
        assertThat(new ResendEmailOtpSender("", "", "https://resend.test").available()).isFalse();
        assertThat(new ResendEmailOtpSender("re_key", "", "https://resend.test").available()).isFalse();
        assertThat(new ResendEmailOtpSender("", "login@medicity.example", "https://resend.test").available()).isFalse();
        assertThat(sender.available()).isTrue();
    }
}
