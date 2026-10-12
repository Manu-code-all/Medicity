package com.medicity.pharmacy;

import com.medicity.privacy.ConsentService;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An administrator creates pharmacist accounts; nobody else can.
 */
@AutoConfigureMockMvc
@DisplayName("Creating pharmacists")
class PharmacistAdminTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired ConsentService consents;

    private User admin;
    private User patient;
    private User pharmacist;
    private String email;

    @BeforeEach
    void setUp() {
        email = "new.pharmacist-" + UUID.randomUUID() + "@medicity.test";
        admin = persistUser("Ops Admin", Role.ADMIN);
        patient = persistUser("Meera Nair", Role.PATIENT);
        pharmacist = persistUser("Existing Pharmacist", Role.PHARMACIST);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE email = ? OR id IN (?, ?, ?)", email,
                admin.getId(), patient.getId(), pharmacist.getId());
    }

    @Test
    @DisplayName("an admin creates a pharmacist who can then sign in and work the counter")
    void adminCreatesPharmacist() throws Exception {
        mvc.perform(post("/api/v1/admin/pharmacists").header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(body(email, "Asha Menon", "temporary-pass-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("Asha Menon"));

        User created = userRepository.findByEmail(email).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.PHARMACIST);
        assertThat(created.getPasswordHash()).doesNotContain("temporary-pass-1");
        // Staff agree to nothing in the app (ConsentService.requiredFor), so no rows and nothing pending.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_records WHERE user_id = ?",
                Integer.class, created.getId())).isZero();
        assertThat(consents.status(created.getId(), Role.PHARMACIST).pending()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'PHARMACIST_CREATED' AND entity_id = ?",
                Integer.class, created.getId().toString())).isEqualTo(1);

        String token = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"temporary-pass-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(token).contains("accessToken");

        mvc.perform(get("/api/v1/pharmacy/stock/low").header("Authorization", bearer(created)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/pharmacists").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + email + "')]").exists());
    }

    @Test
    @DisplayName("only an admin can create or list pharmacists; pharmacists cannot make more")
    void othersAreRefused() throws Exception {
        for (User caller : new User[]{patient, pharmacist}) {
            mvc.perform(post("/api/v1/admin/pharmacists").header("Authorization", bearer(caller))
                            .contentType("application/json")
                            .content(body(email, "Asha Menon", "temporary-pass-1")))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/pharmacists").header("Authorization", bearer(caller)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/admin/pharmacists").contentType("application/json")
                        .content(body(email, "Asha Menon", "temporary-pass-1")))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    @Test
    @DisplayName("a short password and a taken email are refused")
    void validation() throws Exception {
        mvc.perform(post("/api/v1/admin/pharmacists").header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(body(email, "Asha Menon", "short")))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.existsByEmail(email)).isFalse();

        mvc.perform(post("/api/v1/admin/pharmacists").header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(body(pharmacist.getEmail(), "Someone Else", "temporary-pass-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    private static String body(String email, String name, String password) {
        return "{\"email\":\"" + email + "\",\"fullName\":\"" + name + "\",\"temporaryPassword\":\"" + password + "\"}";
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private User persistUser(String name, Role role) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
