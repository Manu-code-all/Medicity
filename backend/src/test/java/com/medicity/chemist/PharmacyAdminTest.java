package com.medicity.chemist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.security.JwtService;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static com.medicity.chemist.ChemistTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding a pharmacy. The properties that matter: only an administrator can
 * do it, the account and the shop come into being together or not at all, the
 * new pharmacist can actually sign in, and a duplicate email or licence is a
 * clean 409 decided by the database rather than a race.
 */
@AutoConfigureMockMvc
@DisplayName("Pharmacy administration")
class PharmacyAdminTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PharmacyRepository pharmacyRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private String adminToken;
    private String patientToken;
    private String pharmacistToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE users CASCADE");
        adminToken = jwtService.issueAccessToken(
                persistUser(userRepository, "admin@medicity.test", "Ops Admin", Role.ADMIN));
        patientToken = jwtService.issueAccessToken(
                persistUser(userRepository, "meera@medicity.test", "Meera Nair", Role.PATIENT));
        Pharmacy existing = persistShop(userRepository, pharmacyRepository, "existing",
                CENTRE_LAT, CENTRE_LNG, Pharmacy.Status.ACTIVE);
        pharmacistToken = jwtService.issueAccessToken(existing.getUser());
    }

    private static String newShop(String email, String licence) {
        return """
                {"email":"%s","password":"a-long-enough-password","ownerName":"Ravi Kumar",
                 "name":"Green Cross Pharmacy","drugLicenceNumber":"%s","phone":"+919822233344",
                 "addressLine":"5 Church Street","city":"Bengaluru","pincode":"560001",
                 "latitude":%s,"longitude":%s,"opensAt":"09:00","closesAt":"21:00"}
                """.formatted(email, licence, northOf(CENTRE_LAT, 0.3), CENTRE_LNG);
    }

    private MvcResult create(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/admin/pharmacies")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    @DisplayName("creates the pharmacist account and the shop together")
    void createsAccountAndShop() throws Exception {
        MvcResult result = create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode created = json.readTree(result.getResponse().getContentAsString());
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("ownerEmail").asText()).isEqualTo("ravi@greencross.test");

        User owner = userRepository.findByEmail("ravi@greencross.test").orElseThrow();
        assertThat(owner.getRole()).isEqualTo(Role.PHARMACIST);
        assertThat(pharmacyRepository.findByUserId(owner.getId())).isPresent();
        // The response never carries the password hash.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");
    }

    @Test
    @DisplayName("the new pharmacist can sign in and reach their own shop")
    void newPharmacistCanLogIn() throws Exception {
        create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ravi@greencross.test\",\"password\":\"a-long-enough-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PHARMACIST"))
                .andReturn();
        String token = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/v1/pharmacies/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Green Cross Pharmacy"))
                .andExpect(jsonPath("$.drugLicenceNumber").value("KA-GC-001"));
    }

    @Test
    @DisplayName("a created shop shows up in nearby search")
    void appearsInSearch() throws Exception {
        create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));

        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", String.valueOf(CENTRE_LAT)).param("lng", String.valueOf(CENTRE_LNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Green Cross Pharmacy")));
    }

    @Test
    @DisplayName("a duplicate email is a 409 and leaves no half-made shop")
    void duplicateEmail() throws Exception {
        assertThat(create(adminToken, newShop("ravi@greencross.test", "KA-GC-001")).getResponse().getStatus())
                .isEqualTo(201);
        long shopsBefore = pharmacyRepository.count();

        MvcResult second = create(adminToken, newShop("ravi@greencross.test", "KA-GC-002"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("EMAIL_TAKEN");
        assertThat(pharmacyRepository.count()).isEqualTo(shopsBefore);
    }

    @Test
    @DisplayName("a duplicate licence is a 409, case-insensitively, and rolls back the account")
    void duplicateLicence() throws Exception {
        assertThat(create(adminToken, newShop("ravi@greencross.test", "KA-GC-001")).getResponse().getStatus())
                .isEqualTo(201);

        MvcResult second = create(adminToken, newShop("sita@other.test", "ka-gc-001"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(second.getResponse().getContentAsString()).get("code").asText())
                .isEqualTo("LICENCE_TAKEN");
        // The user row was inserted first; the shop failing must take it with it.
        assertThat(userRepository.findByEmail("sita@other.test")).isEmpty();
    }

    @Test
    @DisplayName("a short password and bad hours are refused")
    void validation() throws Exception {
        MvcResult shortPassword = create(adminToken,
                newShop("ravi@greencross.test", "KA-GC-001").replace("a-long-enough-password", "short"));
        assertThat(shortPassword.getResponse().getStatus()).isEqualTo(400);

        MvcResult sameHours = create(adminToken,
                newShop("ravi@greencross.test", "KA-GC-001").replace("\"closesAt\":\"21:00\"", "\"closesAt\":\"09:00\""));
        assertThat(sameHours.getResponse().getStatus()).isEqualTo(422);
        assertThat(userRepository.findByEmail("ravi@greencross.test")).isEmpty();
    }

    @Test
    @DisplayName("only an administrator may onboard, list or suspend")
    void adminOnly() throws Exception {
        for (String token : new String[]{patientToken, pharmacistToken}) {
            assertThat(create(token, newShop("x@x.test", "KA-X-1")).getResponse().getStatus()).isEqualTo(403);
            mvc.perform(get("/api/v1/admin/pharmacies").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/pharmacies/" + UUID.randomUUID() + "/suspend")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        assertThat(create("not-a-token", newShop("x@x.test", "KA-X-1")).getResponse().getStatus()).isEqualTo(401);
        assertThat(userRepository.findByEmail("x@x.test")).isEmpty();
    }

    @Test
    @DisplayName("suspending hides a shop from search, reinstating brings it back")
    void suspendAndReinstate() throws Exception {
        MvcResult created = create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/v1/admin/pharmacies/" + id + "/suspend")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", String.valueOf(CENTRE_LAT)).param("lng", String.valueOf(CENTRE_LNG)))
                .andExpect(jsonPath("$[*].name", not(hasItem("Green Cross Pharmacy"))));

        // Suspending twice is fine and writes no second audit row.
        mvc.perform(post("/api/v1/admin/pharmacies/" + id + "/suspend")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        assertThat(auditCount("PHARMACY_SUSPENDED", id)).isEqualTo(1);

        mvc.perform(post("/api/v1/admin/pharmacies/" + id + "/reinstate")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", String.valueOf(CENTRE_LAT)).param("lng", String.valueOf(CENTRE_LNG)))
                .andExpect(jsonPath("$[*].name", hasItem("Green Cross Pharmacy")));
    }

    @Test
    @DisplayName("suspending an unknown shop is a 404")
    void unknownShop() throws Exception {
        mvc.perform(post("/api/v1/admin/pharmacies/" + UUID.randomUUID() + "/suspend")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("onboarding is written to the audit trail")
    void audited() throws Exception {
        MvcResult created = create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        assertThat(auditCount("PHARMACY_CREATED", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("the admin list shows every shop, newest first, with owner emails")
    void list() throws Exception {
        create(adminToken, newShop("ravi@greencross.test", "KA-GC-001"));
        mvc.perform(get("/api/v1/admin/pharmacies").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ownerEmail").value("ravi@greencross.test"))
                .andExpect(jsonPath("$[1].ownerEmail").value("existing@medicity.test"));
    }

    private int auditCount(String action, String entityId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
        return n == null ? 0 : n;
    }
}
