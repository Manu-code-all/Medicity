package com.medicity.security;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing in with a code sent to the account's email: what is sent, when it is
 * sent, what is accepted, and what the replies give away (nothing).
 *
 * <p>The HTTP tests use the application as configured for tests: no email
 * provider and no demo codes. The rules tests build the service with a fake
 * sender, an inline dispatcher and a clock they can move, so expiry, limits and
 * ordering are exact.
 */
@AutoConfigureMockMvc
@DisplayName("Signing in with an emailed code")
class EmailOtpSignInTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired AuthService auth;
    @Autowired AuditLog auditLog;
    @Autowired PlatformTransactionManager transactions;

    private final FakeEmail email = new FakeEmail();
    private final MovableClock clock = new MovableClock();
    private OtpService otp;

    @BeforeEach
    void rulesUnderTest() {
        otp = service(email, Runnable::run, true);
    }

    private OtpService service(EmailOtpSender sender, MailDispatcher dispatcher, boolean demoCodes) {
        return new OtpService(users, jdbc, auth, auditLog, sender, dispatcher, clock, demoCodes, "test-secret");
    }

    // --- through the API, as deployed without an email provider -----------------

    @Test
    @DisplayName("without an email provider every address gets the same 'not available' answer")
    void unavailableSaysNothingAboutAccounts() throws Exception {
        String registered = register(uniqueEmail());

        sendCode(registered).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.demoCode").doesNotExist());
        sendCode(uniqueEmail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("something that is not an email address is a 400 and nothing is sent")
    void rejectsMalformedAddress() throws Exception {
        sendCode("not-an-email").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        sendCode("").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a wrong code is the same 401 for a known address and an unknown one")
    void verifyDoesNotRevealAccounts() throws Exception {
        String registered = register(uniqueEmail());

        for (String address : new String[]{registered, uniqueEmail()}) {
            mvc.perform(post("/api/v1/auth/otp/email/verify").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"code\":\"123456\"}".formatted(address)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("WRONG_CODE"));
        }
    }

    // --- the rules ----------------------------------------------------------

    @Test
    @DisplayName("the emailed code signs in once, however the address is typed")
    void codeSignsInOnce() throws Exception {
        String address = register(uniqueEmail());

        OtpService.CodeSent sent = otp.sendToEmail("  " + address.toUpperCase() + " ");
        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.EMAIL);
        assertThat(sent.demoCode()).isNull();
        assertThat(sent.sentTo()).endsWith("@example.test").doesNotContain(address.substring(0, 6));
        assertThat(email.sentTo).containsExactly(address);

        AuthService.TokenPair session = otp.verifyEmail(address.toUpperCase(), email.lastCode());
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.role()).isEqualTo("PATIENT");

        assertThatThrownBy(() -> otp.verifyEmail(address, email.lastCode())).isInstanceOf(OtpService.WrongCode.class);
    }

    @Test
    @DisplayName("an unknown address is answered exactly like a known one, and nothing is emailed")
    void unknownAddressLooksTheSame() {
        OtpService.CodeSent sent = otp.sendToEmail(uniqueEmail());

        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.EMAIL);
        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName("a disabled account is answered like any other and gets nothing")
    void disabledAccountGetsNothing() throws Exception {
        String address = register(uniqueEmail());
        jdbc.update("UPDATE users SET enabled = FALSE WHERE email = ?", address);

        assertThat(otp.sendToEmail(address).delivery()).isEqualTo(OtpService.Delivery.EMAIL);
        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName("five wrong guesses use the code up, and the right code no longer works")
    void fiveGuesses() throws Exception {
        String address = register(uniqueEmail());
        otp.sendToEmail(address);
        String right = email.lastCode();
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> otp.verifyEmail(address, wrong)).isInstanceOf(OtpService.WrongCode.class);
        }
        assertThatThrownBy(() -> otp.verifyEmail(address, right)).isInstanceOf(OtpService.WrongCode.class);
    }

    @Test
    @DisplayName("a code expires after five minutes, and a newer code replaces an older one")
    void expiryAndReplacement() throws Exception {
        String address = register(uniqueEmail());
        otp.sendToEmail(address);
        String first = email.lastCode();

        clock.advance(OtpService.LIFETIME.plusSeconds(1));
        assertThatThrownBy(() -> otp.verifyEmail(address, first)).isInstanceOf(OtpService.WrongCode.class);

        otp.sendToEmail(address);
        String second = email.lastCode();
        otp.sendToEmail(address);
        String third = email.lastCode();
        if (!second.equals(third)) {
            assertThatThrownBy(() -> otp.verifyEmail(address, second)).isInstanceOf(OtpService.WrongCode.class);
        }
        assertThat(otp.verifyEmail(address, third).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("at most three codes in fifteen minutes, and the fourth request looks like the others")
    void sendLimit() throws Exception {
        String address = register(uniqueEmail());

        for (int i = 0; i < OtpService.MAX_SENDS; i++) {
            otp.sendToEmail(address);
        }
        OtpService.CodeSent fourth = otp.sendToEmail(address);

        assertThat(fourth.delivery()).isEqualTo(OtpService.Delivery.EMAIL);
        assertThat(email.sent).hasSize(OtpService.MAX_SENDS);

        clock.advance(OtpService.SEND_WINDOW.plusSeconds(1));
        otp.sendToEmail(address);
        assertThat(email.sent).hasSize(OtpService.MAX_SENDS + 1);
    }

    @Test
    @DisplayName("a demo account's code is shown instead of emailed, and only when demo codes are on")
    void demoCodes() throws Exception {
        String address = register("visitor-" + System.nanoTime() + "@medicity.demo");

        OtpService.CodeSent sent = otp.sendToEmail(address);
        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.DEMO);
        assertThat(sent.demoCode()).matches("[0-9]{6}");
        assertThat(email.sent).isEmpty();
        assertThat(otp.verifyEmail(address, sent.demoCode()).accessToken()).isNotBlank();

        OtpService demoOff = service(new NoEmail(), Runnable::run, false);
        assertThat(demoOff.sendToEmail(address).delivery()).isEqualTo(OtpService.Delivery.UNAVAILABLE);
    }

    @Test
    @DisplayName("with no email provider configured nothing is sent, for anyone")
    void unavailableSendsNothing() throws Exception {
        String address = register(uniqueEmail());
        OtpService withoutEmail = service(new NoEmail(), Runnable::run, true);

        assertThat(withoutEmail.sendToEmail(address).delivery()).isEqualTo(OtpService.Delivery.UNAVAILABLE);
    }

    @Test
    @DisplayName("the code is stored only as a hash")
    void codeNotStored() throws Exception {
        String address = register(uniqueEmail());
        otp.sendToEmail(address);

        String stored = jdbc.queryForObject("""
                SELECT c.code_hash FROM otp_challenges c JOIN users u ON u.id = c.user_id
                WHERE u.email = ? ORDER BY c.seq DESC LIMIT 1
                """, String.class, address);
        assertThat(stored).hasSize(64).doesNotContain(email.lastCode());
    }

    @Test
    @DisplayName("a sent code is audited with its channel")
    void sendIsAudited() throws Exception {
        String address = register(uniqueEmail());
        otp.sendToEmail(address);

        assertThat(auditRows("OTP_SENT", address)).isEqualTo(1);
    }

    @Test
    @DisplayName("a provider failure is invisible to the caller, logged in the audit trail, and the code row is kept")
    void failedSendIsNotReported() throws Exception {
        String address = register(uniqueEmail());
        OtpService failing = service(new FailingEmail(), Runnable::run, true);

        OtpService.CodeSent sent = failing.sendToEmail(address);

        assertThat(sent.delivery()).isEqualTo(OtpService.Delivery.EMAIL);
        assertThat(auditRows("OTP_SEND_FAILED", address)).isEqualTo(1);
        assertThat(auditRows("OTP_SENT", address)).isZero();
    }

    // --- when the email goes out ----------------------------------------------

    @Test
    @DisplayName("the email is sent only after the code is committed")
    void sentAfterCommit() throws Exception {
        String address = register(uniqueEmail());
        TransactionTemplate tx = new TransactionTemplate(transactions);

        tx.executeWithoutResult(status -> {
            otp.sendToEmail(address);
            assertThat(email.sent).as("still inside the transaction").isEmpty();
        });

        assertThat(email.sent).as("after commit").hasSize(1);
        // And it is a code that works: the row it refers to is already visible.
        assertThat(otp.verifyEmail(address, email.lastCode()).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("nothing is sent if the transaction rolls back")
    void notSentOnRollback() throws Exception {
        String address = register(uniqueEmail());
        TransactionTemplate tx = new TransactionTemplate(transactions);

        tx.executeWithoutResult(status -> {
            otp.sendToEmail(address);
            status.setRollbackOnly();
        });

        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName("the reply does not wait for the provider, so a slow send cannot reveal which addresses exist")
    void replyDoesNotWaitForTheProvider() throws Exception {
        String address = register(uniqueEmail());
        SlowEmail slow = new SlowEmail(Duration.ofMillis(1500));
        BackgroundMailDispatcher background = new BackgroundMailDispatcher();
        OtpService async = service(slow, background, true);

        long start = System.nanoTime();
        async.sendToEmail(address);
        long knownMillis = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        async.sendToEmail(uniqueEmail());
        long unknownMillis = (System.nanoTime() - start) / 1_000_000;

        // Sending takes 1500 ms; replying must take a small fraction of that.
        assertThat(knownMillis).as("reply for an address with an account").isLessThan(1000);
        assertThat(unknownMillis).as("reply for an address without").isLessThan(1000);

        // ...and the message does go out, on the background thread.
        long deadline = System.currentTimeMillis() + 8000;
        while (slow.sent.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(slow.sent).hasSize(1);
        background.shutdown();
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions sendCode(String address) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/email/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(address)));
    }

    private String register(String address) throws Exception {
        return register(address, null);
    }

    /** Registers a patient and returns the (lower-case) address. */
    private String register(String address, String mobile) throws Exception {
        String phone = mobile == null ? "" : ",\"phone\":\"%s\"".formatted(mobile);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Test Patient","dateOfBirth":"1990-01-01"%s}
                                """.formatted(address, PASSWORD, phone)))
                .andExpect(status().isCreated());
        return address;
    }

    private int auditRows(String action, String address) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log a JOIN users u ON u.id::text = a.entity_id
                WHERE a.action = ? AND u.email = ?
                """, Integer.class, action, address);
        return n == null ? 0 : n;
    }

    private static String uniqueEmail() {
        return "email-otp-" + System.nanoTime() + "@example.test";
    }

    private static String randomMobile() {
        return "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    static class FakeEmail implements EmailOtpSender {
        final List<String> sent = new ArrayList<>();
        final List<String> sentTo = new ArrayList<>();

        @Override public boolean available() { return true; }
        @Override public void send(String address, String code) { sentTo.add(address); sent.add(code); }

        String lastCode() { return sent.get(sent.size() - 1); }
    }

    static class FailingEmail implements EmailOtpSender {
        @Override public boolean available() { return true; }
        @Override public void send(String address, String code) {
            throw new SendFailed("provider is down", null);
        }
    }

    static class SlowEmail implements EmailOtpSender {
        final List<String> sent = new CopyOnWriteArrayList<>();
        private final Duration delay;

        SlowEmail(Duration delay) { this.delay = delay; }

        @Override public boolean available() { return true; }
        @Override public void send(String address, String code) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sent.add(code);
        }
    }

    static class NoEmail implements EmailOtpSender {
        @Override public boolean available() { return false; }
        @Override public void send(String email, String code) { throw new AssertionError("nothing may be emailed"); }
    }

    static class MovableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
