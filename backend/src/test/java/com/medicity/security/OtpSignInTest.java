package com.medicity.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.audit.AuditLog;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with a mobile number and a code: what is sent, what is accepted,
 * and what the replies give away (nothing).
 *
 * <p>The HTTP tests use the application as configured for tests: no SMS
 * provider and no demo codes. The rules tests build the service with a fake
 * sender and a clock they can move, so expiry and limits are exact.
 */
@AutoConfigureMockMvc
@DisplayName("Signing in with a mobile number")
class OtpSignInTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AuthService auth;
    @Autowired AuditLog auditLog;

    private final FakeSms sms = new FakeSms();
    private final MovableClock clock = new MovableClock();
    private OtpService otp;

    @BeforeEach
    void rulesUnderTest() {
        otp = new OtpService(users, jdbc, auth, auditLog, sms, clock, true, "test-secret");
    }

    // --- through the API, as deployed without SMS ---------------------------

    @Test
    @DisplayName("without an SMS provider every number gets the same 'not available' answer")
    void unavailableSaysNothingAboutAccounts() throws Exception {
        String registered = register(randomMobile());

        sendCode(registered).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.demoCode").doesNotExist());
        sendCode(randomMobile()).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("UNAVAILABLE"));
        sendCode("12345").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PHONE"));
    }

    @Test
    @DisplayName("a mobile number signs in to one account only: registering it twice is refused")
    void oneAccountPerNumber() throws Exception {
        String mobile = randomMobile();
        register(mobile);

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(uniqueEmail(), mobile.substring(3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_TAKEN"));
    }

    // --- the rules ------------------------------------------------------

    @Test
    @DisplayName("the texted code signs in once, however the number is typed")
    void codeSignsInOnce() throws Exception {
        String mobile = register(randomMobile());

        OtpService.CodeSent sent = otp.send(mobile.substring(1));   // "919876..." without the plus
        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.SMS);
        assertThat(sent.demoCode()).isNull();
        assertThat(sent.sentTo()).startsWith("+91 ").doesNotContain(mobile.substring(3));

        AuthService.TokenPair session = otp.verify(mobile.substring(3), sms.lastCode());
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.role()).isEqualTo("PATIENT");

        assertThatThrownBy(() -> otp.verify(mobile, sms.lastCode())).isInstanceOf(OtpService.WrongCode.class);
    }

    @Test
    @DisplayName("an unknown number is answered exactly like a known one, and nothing is texted")
    void unknownNumberLooksTheSame() {
        OtpService.CodeSent sent = otp.send(randomMobile());

        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.SMS);
        assertThat(sms.sent).isEmpty();
    }

    @Test
    @DisplayName("five wrong guesses use the code up, and the right code no longer works")
    void fiveGuesses() throws Exception {
        String mobile = register(randomMobile());
        otp.send(mobile);
        String right = sms.lastCode();
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> otp.verify(mobile, wrong)).isInstanceOf(OtpService.WrongCode.class);
        }
        assertThatThrownBy(() -> otp.verify(mobile, right)).isInstanceOf(OtpService.WrongCode.class);
    }

    @Test
    @DisplayName("a code expires after five minutes, and a newer code replaces an older one")
    void expiryAndReplacement() throws Exception {
        String mobile = register(randomMobile());
        otp.send(mobile);
        String first = sms.lastCode();

        clock.advance(OtpService.LIFETIME.plusSeconds(1));
        assertThatThrownBy(() -> otp.verify(mobile, first)).isInstanceOf(OtpService.WrongCode.class);

        otp.send(mobile);
        String second = sms.lastCode();
        otp.send(mobile);
        String third = sms.lastCode();
        if (!second.equals(third)) {
            assertThatThrownBy(() -> otp.verify(mobile, second)).isInstanceOf(OtpService.WrongCode.class);
        }
        assertThat(otp.verify(mobile, third).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("at most three codes are texted in fifteen minutes, and the fourth request looks like the others")
    void sendLimit() throws Exception {
        String mobile = register(randomMobile());

        for (int i = 0; i < OtpService.MAX_SENDS; i++) {
            otp.send(mobile);
        }
        OtpService.CodeSent fourth = otp.send(mobile);

        assertThat(fourth.delivery()).isEqualTo(OtpService.Delivery.SMS);
        assertThat(sms.sent).hasSize(OtpService.MAX_SENDS);

        clock.advance(OtpService.SEND_WINDOW.plusSeconds(1));
        otp.send(mobile);
        assertThat(sms.sent).hasSize(OtpService.MAX_SENDS + 1);
    }

    @Test
    @DisplayName("a demo account's code is shown instead of texted, and only when demo codes are on")
    void demoCodes() throws Exception {
        String mobile = randomMobile();
        register("visitor-" + System.nanoTime() + "@medicity.demo", mobile);
        OtpService withoutSms = new OtpService(users, jdbc, auth, auditLog, new NoSms(), clock, true, "test-secret");

        OtpService.CodeSent sent = withoutSms.send(mobile);
        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.DEMO);
        assertThat(sent.demoCode()).matches("[0-9]{6}");
        assertThat(withoutSms.verify(mobile, sent.demoCode()).accessToken()).isNotBlank();

        OtpService demoOff = new OtpService(users, jdbc, auth, auditLog, new NoSms(), clock, false, "test-secret");
        assertThat(demoOff.send(mobile).delivery()).isEqualTo(OtpService.Delivery.UNAVAILABLE);
    }

    @Test
    @DisplayName("the code is stored only as a hash")
    void codeNotStored() throws Exception {
        String mobile = register(randomMobile());
        otp.send(mobile);

        String stored = jdbc.queryForObject("""
                SELECT c.code_hash FROM otp_challenges c JOIN users u ON u.id = c.user_id
                WHERE u.login_phone = ? ORDER BY c.created_at DESC LIMIT 1
                """, String.class, mobile);
        assertThat(stored).hasSize(64).doesNotContain(sms.lastCode());
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions sendCode(String phone) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"%s\"}".formatted(phone)));
    }

    /** Registers a patient on this number; returns it normalised. */
    private String register(String mobile) throws Exception {
        return register(uniqueEmail(), mobile);
    }

    private String register(String email, String mobile) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email, mobile)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body);
        assertThat(created.path("userId").asText()).isNotBlank();
        return mobile;
    }

    private static String registration(String email, String phone) {
        return """
                {"email":"%s","password":"%s","fullName":"Test Patient","dateOfBirth":"1990-01-01","phone":"%s"}
                """.formatted(email, PASSWORD, phone);
    }

    private static String uniqueEmail() {
        return "otp-" + System.nanoTime() + "@example.test";
    }

    /** +91 and ten digits starting 9: a valid Indian mobile that no seed uses. */
    private static String randomMobile() {
        return "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    static class FakeSms implements OtpSender {
        final List<String> sent = new ArrayList<>();

        @Override public boolean available() { return true; }
        @Override public void send(String phone, String code) { sent.add(code); }

        String lastCode() { return sent.get(sent.size() - 1); }
    }

    static class NoSms implements OtpSender {
        @Override public boolean available() { return false; }
        @Override public void send(String phone, String code) { throw new AssertionError("nothing may be texted"); }
    }

    static class MovableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
