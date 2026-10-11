package com.medicity.security;

import com.medicity.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-address limits on sign-up and login (see {@link AddressThrottle}).
 *
 * <p>The limits are set low here (the test profile switches them off for the
 * rest of the suite, which reaches the API from one address). Each test uses
 * its own random client address, because the counts come from the audit log,
 * which is append-only and shared by every test.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "medicity.auth.address-limits-enabled=true",
        "medicity.auth.signup-throttle.max-attempts=3",
        "medicity.auth.address-login-throttle.max-failures=3"})
@DisplayName("Per-address limits")
class AddressThrottleTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${server.tomcat.remoteip.internal-proxies}")
    String trustedProxies;

    @Test
    @DisplayName("a fourth patient sign-up from one address gets 429 with Retry-After; another address is unaffected")
    void patientSignUpsAreLimitedPerAddress() throws Exception {
        String address = randomAddress();
        for (int i = 0; i < 3; i++) {
            registerPatient(address).andExpect(status().isCreated());
        }

        registerPatient(address)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_SIGNUPS"))
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]{0,3}")));

        registerPatient(randomAddress()).andExpect(status().isCreated());

        // The refusal is audited, and is not itself counted against the address.
        assertThat(auditCount("SIGNUP_THROTTLED", address)).isEqualTo(1);
        assertThat(auditCount("SIGNUP_ATTEMPT", address)).isEqualTo(3);
        registerPatient(address).andExpect(status().isTooManyRequests());
        assertThat(auditCount("SIGNUP_ATTEMPT", address)).isEqualTo(3);
    }

    @Test
    @DisplayName("patient, doctor and chemist sign-ups share one allowance per address")
    void allSignUpKindsShareTheAllowance() throws Exception {
        String address = randomAddress();
        registerPatient(address).andExpect(status().isCreated());
        registerDoctor(address).andExpect(status().isCreated());
        registerChemist(address).andExpect(status().isCreated());

        registerPatient(address).andExpect(status().isTooManyRequests());
        registerDoctor(address).andExpect(status().isTooManyRequests());
        registerChemist(address).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a request the validator rejects does not use up the allowance")
    void invalidSignUpsAreNotCounted() throws Exception {
        String address = randomAddress();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/register").with(from(address))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        registerPatient(address).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("failed logins from one address are limited across different emails, and another address is unaffected")
    void failedLoginsAreLimitedPerAddress() throws Exception {
        String address = randomAddress();
        for (int i = 0; i < 3; i++) {
            login(address, "ghost-" + UUID.randomUUID() + "@medicity.test", "wrong-password")
                    .andExpect(status().isUnauthorized());
        }

        // A different email, so the per-email limit has nothing to say; the address is what is limited.
        login(address, "another-" + UUID.randomUUID() + "@medicity.test", "wrong-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]{0,3}")));

        String other = randomAddress();
        String email = "ok-" + UUID.randomUUID() + "@medicity.test";
        registerPatient(other, email).andExpect(status().isCreated());
        login(other, email, PASSWORD).andExpect(status().isOk());

        assertThat(auditCount("LOGIN_THROTTLED", address)).isEqualTo(1);
    }

    @Test
    @DisplayName("trusted proxies default to Tomcat's own private ranges, so setting nothing changes nothing")
    void trustedProxiesDefaultIsTomcatsDefault() {
        assertThat(trustedProxies).isEqualTo(new org.apache.catalina.valves.RemoteIpValve().getInternalProxies());
    }

    // --- helpers --------------------------------------------------------

    /** A private address no other test uses; the audit log's counts are per address. */
    private static String randomAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10.%d.%d.%d".formatted(r.nextInt(1, 255), r.nextInt(0, 255), r.nextInt(1, 255));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private int auditCount(String action, String address) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND ip_address = CAST(? AS inet)",
                Integer.class, action, address);
    }

    private ResultActions registerPatient(String address) throws Exception {
        return registerPatient(address, "limit-" + UUID.randomUUID() + "@medicity.test");
    }

    private ResultActions registerPatient(String address, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","fullName":"Test Patient","dateOfBirth":"1990-01-01"}
                        """.formatted(email, PASSWORD)));
    }

    private ResultActions registerDoctor(String address) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return mvc.perform(post("/api/v1/auth/register/doctor").with(from(address))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"dr-%s@medicity.test","password":"%s","fullName":"Dr. Limit","phone":"9%09d",
                         "specialization":"Dermatology","medicalCouncil":"Karnataka Medical Council",
                         "registrationNumber":"KMC-%s","qualification":"MBBS, MD","yearsExperience":6,
                         "consultationFee":800}
                        """.formatted(suffix, PASSWORD, ThreadLocalRandom.current().nextInt(100_000_000, 999_999_999),
                        suffix)));
    }

    private ResultActions registerChemist(String address) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return mvc.perform(post("/api/v1/auth/register/chemist").with(from(address))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"chem-%s@medicity.test","password":"%s","fullName":"Ravi Kumar",
                         "store":{"name":"Limit Medicals","licenceNumber":"KA-%s","phone":"+919800000001",
                                  "addressLine":"1 Main Road","city":"Bengaluru",
                                  "latitude":12.97,"longitude":77.64,"opensAt":"08:00","closesAt":"22:00",
                                  "open24h":false,"holdHours":3}}
                        """.formatted(suffix, PASSWORD, suffix)));
    }

    private ResultActions login(String address, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(from(address))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }
}
