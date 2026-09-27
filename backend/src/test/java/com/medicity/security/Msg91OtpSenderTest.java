package com.medicity.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The MSG91 call, against a mock server. */
@DisplayName("Texting codes through MSG91")
class Msg91OtpSenderTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://msg91.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Msg91OtpSender sender = new Msg91OtpSender(builder.build(), "test-key", "tmpl-1");

    @Test
    @DisplayName("sends our code to 91XXXXXXXXXX with the template and key")
    void sends() {
        server.expect(requestTo("https://msg91.test/api/v5/otp?template_id=tmpl-1&mobile=919876500101&otp=482913"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("authkey", "test-key"))
                .andRespond(withSuccess("{\"type\":\"success\",\"request_id\":\"abc\"}", MediaType.APPLICATION_JSON));

        sender.send("+919876500101", "482913");
        server.verify();
    }

    @Test
    @DisplayName("a refusal or an outage is a clean 503, not a crash")
    void failsCleanly() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://msg91.test/api/v5/otp")))
                .andRespond(withSuccess("{\"type\":\"error\",\"message\":\"Invalid template\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> sender.send("+919876500101", "482913")).isInstanceOf(OtpSender.SendFailed.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://msg91.test/api/v5/otp"))).andRespond(withServerError());
        assertThatThrownBy(() -> sender.send("+919876500101", "482913")).isInstanceOf(OtpSender.SendFailed.class);
    }

    @Test
    @DisplayName("without both settings it is not available")
    void availability() {
        assertThat(new Msg91OtpSender("", "", "https://msg91.test").available()).isFalse();
        assertThat(new Msg91OtpSender("key", "", "https://msg91.test").available()).isFalse();
        assertThat(sender.available()).isTrue();
    }
}
