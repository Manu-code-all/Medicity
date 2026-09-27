package com.medicity.store;

import com.medicity.outbox.OutboxRelay;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chemists' stores: sign-up, the licence check, profile edits and "near me". */
@AutoConfigureMockMvc
@DisplayName("Stores")
class StoreTest extends AbstractIntegrationTest {

    /** Indiranagar, Bengaluru. */
    private static final double LAT = 12.9719;
    private static final double LNG = 77.6412;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired OutboxRelay relay;
    @Autowired JwtService jwtService;

    private User patient;
    private User admin;

    @BeforeEach
    void setUp() {
        cleanUp();
        patient = persistUser(Role.PATIENT, "Meera Nair");
        admin = persistUser(Role.ADMIN, "Ops Admin");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // --- sign-up and verification ----------------------------------------

    @Test
    @DisplayName("a chemist signs up with their store, which waits for the licence check before anyone finds it")
    void signUpStartsUnverified() throws Exception {
        String token = registerChemist("sai@store.test", "KA-B1-20/21-1001", LAT + 0.004, LNG);

        mvc.perform(get("/api/v1/stores/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.licenceNumber").value("KA-B1-20/21-1001"))
                .andExpect(jsonPath("$.holdHours").value(3));

        nearby(3000).andExpect(jsonPath("$", hasSize(0)));
        assertThat(eventCount("STORE_REGISTERED")).isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator verifies the store; it then appears nearby and its owner is told")
    void verificationPublishesTheStore() throws Exception {
        registerChemist("sai@store.test", "KA-B1-20/21-1001", LAT + 0.004, LNG);
        UUID storeId = storeRepository.findUnverified().get(0).getId();

        mvc.perform(get("/api/v1/admin/stores/pending").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sri Sai Medicals"));

        // Only an administrator may vouch for a licence.
        mvc.perform(post("/api/v1/admin/stores/" + storeId + "/verify").header("Authorization", bearer(patient)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/stores/" + storeId + "/verify").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(post("/api/v1/admin/stores/" + storeId + "/verify").header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));

        nearby(3000).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Sri Sai Medicals"));

        relay.drain();
        User owner = userRepository.findByEmail("sai@store.test").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND title = ?",
                Integer.class, owner.getId(), "Your store is verified")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND title = ?",
                Integer.class, admin.getId(), "Store awaiting verification")).isEqualTo(1);
    }

    @Test
    @DisplayName("a licence registers one store, whatever its case; the second sign-up leaves no account behind")
    void licenceIsUnique() throws Exception {
        registerChemist("sai@store.test", "KA-B1-20/21-1001", LAT, LNG);

        mvc.perform(post("/api/v1/auth/register/chemist").contentType(MediaType.APPLICATION_JSON)
                        .content(registration("copy@store.test", "ka-b1-20/21-1001", LAT, LNG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LICENCE_TAKEN"));

        // The account and the store are one unit: no chemist without a store.
        assertThat(userRepository.findByEmail("copy@store.test")).isEmpty();
    }

    @Test
    @DisplayName("store sign-up never creates a patient, doctor or admin, whatever the body says")
    void signUpCannotChooseItsRole() throws Exception {
        String body = registration("sneaky@store.test", "KA-B1-20/21-2002", LAT, LNG)
                .replaceFirst("\\{", "{\"role\":\"ADMIN\",");
        mvc.perform(post("/api/v1/auth/register/chemist").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CHEMIST"));
    }

    // --- profile ----------------------------------------------------------

    @Test
    @DisplayName("the owner edits hours and hold time within the rules; the licence stays what was verified")
    void profileEdits() throws Exception {
        String token = registerChemist("sai@store.test", "KA-B1-20/21-1001", LAT, LNG);

        mvc.perform(put("/api/v1/stores/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("OTHER-LICENCE", LAT, LNG, "20:00", "02:00", false, 4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.licenceNumber").value("KA-B1-20/21-1001"))
                .andExpect(jsonPath("$.closesAt").value("02:00:00"))
                .andExpect(jsonPath("$.holdHours").value(4));

        // Longer than 4 hours is not "keeping it aside", it is a second stockroom.
        mvc.perform(put("/api/v1/stores/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("X", LAT, LNG, "09:00", "21:00", false, 5)))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/api/v1/stores/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(storeJson("X", LAT, LNG, "09:00", "09:00", false, 3)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_HOURS"));

        // A patient has no store to edit.
        mvc.perform(get("/api/v1/stores/me").header("Authorization", bearer(patient)))
                .andExpect(status().isForbidden());
    }

    // --- near me ------------------------------------------------------------

    @Test
    @DisplayName("near me: only verified, active stores inside the circle, nearest first, with true distances")
    void nearbyIsACircleNotASquare() throws Exception {
        // 0.01 degrees of latitude is 1,112 m.
        persistStore("Near", LAT + 0.005, LNG, true, true);              // ~556 m north
        persistStore("Middle", LAT, LNG + 0.02, true, true);             // ~2.17 km east
        persistStore("Far", LAT + 0.06, LNG, true, true);                // ~6.7 km
        persistStore("Unverified", LAT + 0.001, LNG, false, true);
        persistStore("Closed down", LAT + 0.001, LNG, true, false);
        // Inside the 3 km square but outside the circle: 2.5 km north and east
        // is 3.4 km away diagonally.
        persistStore("Corner", LAT + 0.0225, LNG + 0.023, true, true);

        nearby(3000)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Near"))
                .andExpect(jsonPath("$[0].distanceM").value(closeTo(556.0, 5.0), Double.class))
                .andExpect(jsonPath("$[1].name").value("Middle"))
                .andExpect(jsonPath("$[1].distanceM").value(closeTo(2169.0, 15.0), Double.class));

        nearby(10_000).andExpect(jsonPath("$", hasSize(4)));

        mvc.perform(get("/api/v1/stores/nearby").param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .param("radiusM", "50000").header("Authorization", bearer(patient)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RADIUS"));
    }

    @Test
    @DisplayName("open now follows each store's own hours, including past midnight and 24 hours")
    void openNow() throws Exception {
        Store allNight = persistStore("All night", LAT + 0.001, LNG, true, true);
        allNight.setOpen24h(true);
        storeRepository.save(allNight);

        nearby(3000).andExpect(jsonPath("$[0].openNow").value(true));
    }

    // --- helpers ------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions nearby(int radiusM) throws Exception {
        return mvc.perform(get("/api/v1/stores/nearby").param("lat", String.valueOf(LAT))
                        .param("lng", String.valueOf(LNG)).param("radiusM", String.valueOf(radiusM))
                        .header("Authorization", bearer(patient)))
                .andExpect(status().isOk());
    }

    private String registerChemist(String email, String licence, double lat, double lng) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/register/chemist").contentType(MediaType.APPLICATION_JSON)
                        .content(registration(email, licence, lat, lng)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CHEMIST"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
    }

    private static String registration(String email, String licence, double lat, double lng) {
        return """
                {"email":"%s","password":"a-long-enough-password","fullName":"Ravi Kumar","store":%s}
                """.formatted(email, storeJson(licence, lat, lng, "08:00", "22:00", false, 3));
    }

    private static String storeJson(String licence, double lat, double lng, String opens, String closes,
                                    boolean open24h, int holdHours) {
        return """
                {"name":"Sri Sai Medicals","licenceNumber":"%s","phone":"+919800000001",
                 "addressLine":"12, 100 Feet Road","city":"Bengaluru","latitude":%s,"longitude":%s,
                 "opensAt":"%s","closesAt":"%s","open24h":%s,"holdHours":%d}
                """.formatted(licence, lat, lng, opens, closes, open24h, holdHours);
    }

    private Store persistStore(String name, double lat, double lng, boolean verified, boolean active) {
        User owner = persistUser(Role.CHEMIST, name + " owner");
        return storeRepository.save(Store.builder()
                .owner(owner).name(name).licenceNumber("LIC-" + UUID.randomUUID().toString().substring(0, 12))
                .phone("+919800000009").addressLine("Somewhere").city("Bengaluru")
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .opensAt(LocalTime.of(8, 0)).closesAt(LocalTime.of(22, 0))
                .verifiedAt(verified ? Instant.now() : null).active(active)
                .build());
    }

    private int eventCount(String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Integer.class, type);
    }

    private User persistUser(Role role, String name) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@store.test");
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM stores");
        List<UUID> ids = jdbc.queryForList("SELECT id FROM users WHERE email LIKE '%@store.test'", UUID.class);
        userRepository.deleteAllById(ids);
    }
}
