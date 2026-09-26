package com.medicity.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh-token rotation, reuse detection, sign-out and the login limit,
 * driven through the HTTP API.
 *
 * <p>Every test registers its own account with a random email. Failed logins
 * are counted from the audit log, which no test can clear (it is append-only),
 * so a shared email would carry failures from one test into the next.
 */
@AutoConfigureMockMvc
@DisplayName("Sessions and login limits")
class SessionSecurityTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;

    // --- refresh-token rotation -----------------------------------------

    @Test
    @DisplayName("a refresh token works once and is replaced by a new one")
    void refreshRotates() throws Exception {
        String first = register().path("refreshToken").asText();

        String second = refresh(first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String next = json.readTree(second).path("refreshToken").asText();

        assertThat(next).isNotEqualTo(first);
        refresh(next).andExpect(status().isOk());
    }

    @Test
    @DisplayName("reusing a spent refresh token ends the whole session, including its newest token")
    void reuseRevokesTheFamily() throws Exception {
        JsonNode session = register();
        String stolen = session.path("refreshToken").asText();
        UUID userId = UUID.fromString(session.path("userId").asText());

        // The legitimate client refreshes; the thief still holds the old token.
        String current = json.readTree(refresh(stolen).andReturn().getResponse().getContentAsString())
                .path("refreshToken").asText();

        refresh(stolen).andExpect(status().isUnauthorized());
        // The token the legitimate client holds is gone too: the server cannot
        // tell which party is which, so both must sign in again.
        refresh(current).andExpect(status().isUnauthorized());

        Integer alerts = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log
                WHERE action = 'REFRESH_TOKEN_REUSED' AND actor_id = ? AND outcome = 'DENIED'
                """, Integer.class, userId);
        assertThat(alerts).isEqualTo(1);
    }

    @Test
    @DisplayName("reuse in one session does not sign the user out of their other sessions")
    void reuseIsScopedToOneSession() throws Exception {
        String email = uniqueEmail();
        register(email);
        String laptop = login(email).path("refreshToken").asText();
        String phone = login(email).path("refreshToken").asText();

        refresh(laptop).andExpect(status().isOk());
        refresh(laptop).andExpect(status().isUnauthorized());   // reuse: laptop session ends

        refresh(phone).andExpect(status().isOk());
    }

    @Test
    @DisplayName("of simultaneous refreshes with one token, at most one succeeds, and the session ends")
    void concurrentRefreshesCannotFork() throws Exception {
        String token = register().path("refreshToken").asText();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return authService.refresh(token).refreshToken();
                } catch (BadCredentialsException e) {
                    return null;
                }
            }));
        }
        start.countDown();
        List<String> issued = new ArrayList<>();
        for (Future<String> result : results) {
            String value = result.get(30, TimeUnit.SECONDS);
            if (value != null) issued.add(value);
        }
        pool.shutdown();

        // The conditional UPDATE lets exactly one claim the token; every other
        // attempt is a reuse, which then revokes the winner's new token as well.
        assertThat(issued).hasSize(1);
        refresh(issued.get(0)).andExpect(status().isUnauthorized());
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM refresh_tokens t
                JOIN refresh_tokens original ON original.family_id = t.family_id
                WHERE original.token_hash = ? AND t.used_at IS NULL AND t.revoked_at IS NULL
                """, Integer.class, (Object) RefreshTokenStore.hash(token));
        assertThat(live).isZero();
    }

    @Test
    @DisplayName("signing out revokes the refresh token, and never fails")
    void logoutRevokes() throws Exception {
        String token = register().path("refreshToken").asText();

        logout(token).andExpect(status().isNoContent());
        refresh(token).andExpect(status().isUnauthorized());

        // Unknown or already-revoked tokens get the same answer.
        logout(token).andExpect(status().isNoContent());
        logout("not-a-real-token").andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("an access token is not accepted as a refresh token")
    void accessTokenIsNotARefreshToken() throws Exception {
        String access = register().path("accessToken").asText();
        refresh(access).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a disabled account cannot refresh")
    void disabledAccountCannotRefresh() throws Exception {
        JsonNode session = register();
        User user = userRepository.findById(UUID.fromString(session.path("userId").asText())).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        refresh(session.path("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("only a hash of the refresh token is stored")
    void onlyTheHashIsStored() throws Exception {
        String token = register().path("refreshToken").asText();

        Integer byHash = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class, (Object) RefreshTokenStore.hash(token));
        Integer byValue = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = convert_to(?, 'UTF8')",
                Integer.class, token);
        assertThat(byHash).isEqualTo(1);
        assertThat(byValue).isZero();
    }

    // --- login limit ----------------------------------------------------

    @Test
    @DisplayName("after 5 failed logins, even the right password gets 429 with Retry-After")
    void failedLoginsAreLimited() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 5; i++) {
            attemptLogin(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }

        attemptLogin(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]{0,2}")));

        Integer throttled = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'LOGIN_THROTTLED' AND detail ->> 'email' = ?
                """, Integer.class, email);
        assertThat(throttled).isEqualTo(1);
    }

    @Test
    @DisplayName("failures below the limit do not block the right password")
    void failuresBelowTheLimitAreAllowed() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 4; i++) {
            attemptLogin(email, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        attemptLogin(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the limit is per account: another account is unaffected")
    void limitIsPerAccount() throws Exception {
        String victim = uniqueEmail();
        String bystander = uniqueEmail();
        register(victim);
        register(bystander);
        for (int i = 0; i < 5; i++) {
            attemptLogin(victim, "wrong-password-" + i);
        }

        attemptLogin(bystander, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown email is limited exactly like a real one, so a 429 reveals nothing")
    void unknownEmailIsLimitedTheSame() throws Exception {
        String ghost = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            attemptLogin(ghost, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        attemptLogin(ghost, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    // --- helpers --------------------------------------------------------

    private static String uniqueEmail() {
        return "session-" + UUID.randomUUID() + "@medicity.test";
    }

    private JsonNode register() throws Exception {
        return register(uniqueEmail());
    }

    private JsonNode register(String email) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Test Patient","dateOfBirth":"1990-01-01"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode login(String email) throws Exception {
        return json.readTree(attemptLogin(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions attemptLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(token)));
    }

    private ResultActions logout(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(token)));
    }
}
