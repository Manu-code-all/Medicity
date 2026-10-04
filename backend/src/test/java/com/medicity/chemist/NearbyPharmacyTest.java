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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.medicity.chemist.ChemistTestSupport.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Near me", through the real HTTP stack and a real database.
 *
 * <p>The distance maths and the bounding-box prefilter live in one SQL
 * statement, so they are exercised together here: a shop the box wrongly
 * excluded, or one the exact distance wrongly kept, shows up as a wrong list.
 */
@AutoConfigureMockMvc
@DisplayName("Nearby pharmacies")
class NearbyPharmacyTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PharmacyRepository pharmacyRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    private String patientToken;

    @BeforeEach
    void setUp() {
        // Cascades to every table that references users, which is the whole
        // object graph; the container is shared, so other suites' rows are here.
        jdbc.execute("TRUNCATE users CASCADE");

        User patient = persistUser(userRepository, "meera@medicity.test", "Meera Nair", Role.PATIENT);
        patientToken = jwtService.issueAccessToken(patient);

        persistShop(userRepository, pharmacyRepository, "near",
                northOf(CENTRE_LAT, 1.0), CENTRE_LNG, Pharmacy.Status.ACTIVE);
        persistShop(userRepository, pharmacyRepository, "mid",
                CENTRE_LAT, eastOf(CENTRE_LAT, CENTRE_LNG, 4.0), Pharmacy.Status.ACTIVE);
        persistShop(userRepository, pharmacyRepository, "far",
                northOf(CENTRE_LAT, -12.0), CENTRE_LNG, Pharmacy.Status.ACTIVE);
        persistShop(userRepository, pharmacyRepository, "closedforever",
                CENTRE_LAT, eastOf(CENTRE_LAT, CENTRE_LNG, -0.5), Pharmacy.Status.SUSPENDED);
    }

    private MockHttpServletRequestBuilder search() {
        return get("/api/v1/pharmacies/nearby")
                .header("Authorization", "Bearer " + patientToken)
                .param("lat", String.valueOf(CENTRE_LAT))
                .param("lng", String.valueOf(CENTRE_LNG));
    }

    @Test
    @DisplayName("lists shops inside the default radius, nearest first, with their distance")
    void nearestFirst() throws Exception {
        mvc.perform(search())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("near"))
                .andExpect(jsonPath("$[0].distanceKm").value(closeTo(1.0, 0.03)))
                .andExpect(jsonPath("$[1].name").value("mid"))
                .andExpect(jsonPath("$[1].distanceKm").value(closeTo(4.0, 0.03)))
                // Every field the screen renders comes through, including the
                // camelCase address, which a lower-cased SQL alias would lose.
                .andExpect(jsonPath("$[0].addressLine").value("1 near Road"))
                .andExpect(jsonPath("$[0].city").value("Bengaluru"))
                .andExpect(jsonPath("$[0].phone").value("+919876500000"));
    }

    @Test
    @DisplayName("a wider radius brings in farther shops; a narrower one drops them")
    void radiusIsHonoured() throws Exception {
        mvc.perform(search().param("radiusKm", "15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("near", "mid", "far")));

        mvc.perform(search().param("radiusKm", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("near")));
    }

    @Test
    @DisplayName("a suspended shop never appears")
    void suspendedShopIsHidden() throws Exception {
        mvc.perform(search().param("radiusKm", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", not(hasItem("closedforever"))));
    }

    @Test
    @DisplayName("limit caps the number of results")
    void limitCaps() throws Exception {
        mvc.perform(search().param("radiusKm", "15").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("near"));
    }

    @Test
    @DisplayName("nothing in range is an empty list, not an error")
    void emptyResult() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", "28.6139").param("lng", "77.2090"))   // Delhi
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("openNow reflects the shop's hours")
    void openNow() throws Exception {
        // The fixture shops are open 00:00-23:59, so they are open except in the
        // final minute of the day; the rule itself is tested in PharmacyHoursTest.
        mvc.perform(search())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].opensAt").value("00:00"))
                .andExpect(jsonPath("$[0].closesAt").value("23:59"))
                .andExpect(jsonPath("$[0].openNow").isBoolean());
    }

    @Test
    @DisplayName("a point outside the globe is rejected with a stable code")
    void rejectsImpossibleCoordinates() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", "91").param("lng", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));

        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", "0").param("lng", "181"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));

        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", "NaN").param("lng", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOCATION"));
    }

    @Test
    @DisplayName("a zero, negative or oversized radius is rejected")
    void rejectsBadRadius() throws Exception {
        for (String radius : new String[]{"0", "-3", "51"}) {
            mvc.perform(search().param("radiusKm", radius))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RADIUS"));
        }
    }

    @Test
    @DisplayName("a missing coordinate is a 400, not a crash")
    void missingCoordinate() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .header("Authorization", "Bearer " + patientToken)
                        .param("lat", "12.97"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("requires a signed-in user")
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/pharmacies/nearby")
                        .param("lat", String.valueOf(CENTRE_LAT)).param("lng", String.valueOf(CENTRE_LNG)))
                .andExpect(status().isUnauthorized());
    }
}
