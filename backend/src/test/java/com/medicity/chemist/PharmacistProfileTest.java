package com.medicity.chemist;

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

import static com.medicity.chemist.ChemistTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A pharmacist reading and editing their own shop. The routes carry no shop id,
 * so the property under test is that "mine" really is the caller's and no one
 * else's, and that the fields only an administrator vouches for stay put.
 */
@AutoConfigureMockMvc
@DisplayName("Pharmacist shop profile")
class PharmacistProfileTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PharmacyRepository pharmacyRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private Pharmacy mine;
    private Pharmacy theirs;
    private String myToken;
    private String patientToken;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE users CASCADE");
        mine = persistShop(userRepository, pharmacyRepository, "mine", CENTRE_LAT, CENTRE_LNG, Pharmacy.Status.ACTIVE);
        theirs = persistShop(userRepository, pharmacyRepository, "theirs", CENTRE_LAT, CENTRE_LNG, Pharmacy.Status.ACTIVE);
        myToken = jwtService.issueAccessToken(mine.getUser());
        User patient = persistUser(userRepository, "meera@medicity.test", "Meera Nair", Role.PATIENT);
        patientToken = jwtService.issueAccessToken(patient);
    }

    private static String body(String name, String phone, double lat, String opens, String closes) {
        return """
                {"name":"%s","phone":"%s","addressLine":"22 MG Road","city":"Bengaluru","pincode":"560001",
                 "latitude":%s,"longitude":77.6,"opensAt":"%s","closesAt":"%s"}
                """.formatted(name, phone, lat, opens, closes);
    }

    @Test
    @DisplayName("reads its own shop, including the licence")
    void readsOwnShop() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/me").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mine.getId().toString()))
                .andExpect(jsonPath("$.name").value("mine"))
                .andExpect(jsonPath("$.drugLicenceNumber").value("KA-MINE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("updates its own shop and nothing of anyone else's")
    void updatesOnlyOwnShop() throws Exception {
        mvc.perform(put("/api/v1/pharmacies/me")
                        .header("Authorization", "Bearer " + myToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Sunrise Chemists", "+919811122233", 12.95, "08:30", "22:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sunrise Chemists"))
                .andExpect(jsonPath("$.opensAt").value("08:30:00"))
                .andExpect(jsonPath("$.latitude").value(12.95));

        Pharmacy updated = pharmacyRepository.findById(mine.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Sunrise Chemists");
        assertThat(updated.getPhone()).isEqualTo("+919811122233");

        Pharmacy untouched = pharmacyRepository.findById(theirs.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("theirs");
    }

    @Test
    @DisplayName("cannot change its licence or status, even by sending them")
    void cannotChangeAdminOwnedFields() throws Exception {
        mvc.perform(put("/api/v1/pharmacies/me")
                        .header("Authorization", "Bearer " + myToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"mine","phone":"+919811122233","addressLine":"22 MG Road","city":"Bengaluru",
                                 "latitude":12.95,"longitude":77.6,"opensAt":"09:00","closesAt":"21:00",
                                 "drugLicenceNumber":"STOLEN-1","status":"SUSPENDED"}
                                """))
                .andExpect(status().isOk());

        Pharmacy after = pharmacyRepository.findById(mine.getId()).orElseThrow();
        assertThat(after.getDrugLicenceNumber()).isEqualTo("KA-MINE");
        assertThat(after.getStatus()).isEqualTo(Pharmacy.Status.ACTIVE);
    }

    @Test
    @DisplayName("invalid fields are reported field by field")
    void validation() throws Exception {
        mvc.perform(put("/api/v1/pharmacies/me")
                        .header("Authorization", "Bearer " + myToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "12345", 95, "09:00", "21:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasKey("name")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("phone")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("latitude")));
    }

    @Test
    @DisplayName("opening and closing at the same time is refused")
    void sameHours() throws Exception {
        mvc.perform(put("/api/v1/pharmacies/me")
                        .header("Authorization", "Bearer " + myToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mine", "+919811122233", 12.95, "09:00", "09:00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_HOURS"));
    }

    @Test
    @DisplayName("a patient is refused, and an anonymous caller is unauthenticated")
    void wrongRoles() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/me").header("Authorization", "Bearer " + patientToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/pharmacies/me")
                        .header("Authorization", "Bearer " + patientToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", "+919811122233", 12.95, "09:00", "21:00")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pharmacies/me")).andExpect(status().isUnauthorized());
    }
}
