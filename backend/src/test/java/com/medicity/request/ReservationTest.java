package com.medicity.request;

import com.jayway.jsonpath.JsonPath;
import com.medicity.jobs.ReservationExpiryJob;
import com.medicity.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reserving at one store and collecting with the pick-up code. */
@AutoConfigureMockMvc
@DisplayName("Reserve and pick up")
class ReservationTest extends NetworkTestSupport {

    @Autowired ReservationService reservations;
    @Autowired ReservationExpiryJob expiryJob;

    @Test
    @DisplayName("reserving holds the store's answer for its hold time, with a code only the patient sees")
    void reserveGivesTheCodeToThePatientOnly() throws Exception {
        UUID id = answeredByBoth();

        String body = reserve(id, near.getId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.reservation.status").value("HELD"))
                .andExpect(jsonPath("$.reservation.storeName").value("Sri Sai Medicals"))
                .andExpect(jsonPath("$.reservation.pickupCode", matchesPattern("[0-9]{6}")))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.reservation.pickupCode");
        Instant expires = Instant.parse(JsonPath.read(body, "$.reservation.expiresAt"));
        assertThat(Duration.between(clock.instant(), expires)).isBetween(Duration.ofMinutes(179), Duration.ofHours(3));

        String storeList = mvc.perform(get("/api/v1/stores/me/reservations").header("Authorization", bearer(nearOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // Now that she chose this store, it gets her full name to hand over.
                .andExpect(jsonPath("$[0].patientName").value("Meera Nair"))
                .andExpect(jsonPath("$[0].lines", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(storeList).doesNotContain(code).doesNotContain("pickupCode");

        // The question is no longer open: other stores stop seeing it.
        queue(quietOwner).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("only a store that answered with something can be reserved at")
    void onlyAnsweredStores() throws Exception {
        UUID id = ask(3000);
        answer(nearOwner, id, lines(line(omeprazole, "NO", null, null, null), line(cetirizine, "NO", null, null, null)))
                .andExpect(status().isOk());

        reserve(id, near.getId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_RESERVE"));
        reserve(id, nearer.getId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_ANSWERED"));
    }

    @Test
    @DisplayName("two stores reserved at the same moment: exactly one reservation holds")
    void oneReservationPerQuestion() throws Exception {
        UUID id = answeredByBoth();

        int wins = race(8, i -> reservations.reserve(meeraUser.getId(), id,
                i % 2 == 0 ? near.getId() : nearer.getId()));

        assertThat(wins).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE request_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the right code hands over the medicines once; the prescription shows where it was collected")
    void collectWithTheCode() throws Exception {
        UUID id = answeredByBoth();
        String code = codeOf(reserve(id, near.getId()));
        UUID reservationId = reservationOf(id);

        // Another store cannot collect it, even with the code.
        collect(nearerOwner, reservationId, code).andExpect(status().isNotFound());

        collect(nearOwner, reservationId, code).andExpect(status().isNoContent());
        collect(nearOwner, reservationId, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_HELD"));

        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, id))
                .isEqualTo("CLOSED");
        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$[0].collectedFrom").value("Sri Sai Medicals"))
                .andExpect(jsonPath("$[0].collectedAt").exists());
        // Once collected, the code is no longer shown.
        mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id).header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.reservation.status").value("COLLECTED"))
                .andExpect(jsonPath("$.reservation.pickupCode").doesNotExist());
    }

    @Test
    @DisplayName("wrong codes are counted even though they fail, and five lock the reservation")
    void wrongCodesAreCappedAndCounted() throws Exception {
        UUID id = answeredByBoth();
        String code = codeOf(reserve(id, near.getId()));
        UUID reservationId = reservationOf(id);
        String wrong = code.equals("000000") ? "111111" : "000000";

        collect(nearOwner, reservationId, wrong).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WRONG_PICKUP_CODE"))
                .andExpect(jsonPath("$.detail").value("Wrong code. 4 attempts left."));
        // The refusal rolled nothing back: the attempt is on record.
        assertThat(attempts(reservationId)).isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            collect(nearOwner, reservationId, wrong).andExpect(status().isUnprocessableEntity());
        }
        collect(nearOwner, reservationId, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODE_LOCKED"));
        assertThat(attempts(reservationId)).isEqualTo(5);

        collect(nearOwner, reservationId, "12ab").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an uncollected reservation expires: both sides are told and the question reopens")
    void expiry() throws Exception {
        UUID id = answeredByBoth();
        String code = codeOf(reserve(id, near.getId()));
        UUID reservationId = reservationOf(id);
        jdbc.update("""
                UPDATE reservations SET created_at = now() - interval '4 hours', expires_at = now() - interval '1 minute'
                WHERE id = ?
                """, reservationId);

        assertThat(expiryJob.run().reservations()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class, reservationId))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, id))
                .isEqualTo("OPEN");
        collect(nearOwner, reservationId, code).andExpect(status().isConflict());

        relay.drain();
        assertThat(notificationsFor(meeraUser, "Your reservation expired")).isEqualTo(1);
        assertThat(notificationsFor(nearOwner, "Reservation not collected")).isEqualTo(1);

        // And she can reserve somewhere else.
        reserve(id, nearer.getId()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the expiry job also closes questions past their six hours")
    void questionsExpire() throws Exception {
        UUID id = ask(3000);
        jdbc.update("""
                UPDATE medicine_requests SET created_at = now() - interval '7 hours', expires_at = now() - interval '1 hour'
                WHERE id = ?
                """, id);

        assertThat(expiryJob.run().questions()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, id))
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("the patient cancels; the store is told and she can reserve elsewhere. Nobody else can cancel it.")
    void cancel() throws Exception {
        UUID id = answeredByBoth();
        reserve(id, near.getId()).andExpect(status().isCreated());
        UUID reservationId = reservationOf(id);

        mvc.perform(post("/api/v1/patients/me/reservations/" + reservationId + "/cancel")
                        .header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/patients/me/reservations/" + reservationId + "/cancel")
                        .header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());

        relay.drain();
        assertThat(notificationsFor(nearOwner, "Reservation cancelled")).isEqualTo(1);
        reserve(id, nearer.getId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservation.storeName").value("Green Cross"));
    }

    // --- helpers ------------------------------------------------------------------

    /** A question both near stores answered in full. */
    private UUID answeredByBoth() throws Exception {
        UUID id = ask(3000);
        answer(nearOwner, id, allYes(5.0, 2.0)).andExpect(status().isOk());
        answer(nearerOwner, id, allYes(6.0, 3.0)).andExpect(status().isOk());
        return id;
    }

    private ResultActions reserve(UUID requestId, UUID storeId) throws Exception {
        return mvc.perform(post("/api/v1/patients/me/medicine-requests/" + requestId + "/reserve")
                .header("Authorization", bearer(meeraUser)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":\"" + storeId + "\"}"));
    }

    private ResultActions collect(User owner, UUID reservationId, String code) throws Exception {
        return mvc.perform(post("/api/v1/stores/me/reservations/" + reservationId + "/collect")
                .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private static String codeOf(ResultActions reserved) throws Exception {
        return JsonPath.read(reserved.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.reservation.pickupCode");
    }

    private UUID reservationOf(UUID requestId) {
        return jdbc.queryForObject("SELECT id FROM reservations WHERE request_id = ? AND status = 'HELD'",
                UUID.class, requestId);
    }

    private int attempts(UUID reservationId) {
        return jdbc.queryForObject("SELECT wrong_code_attempts FROM reservations WHERE id = ?", Integer.class,
                reservationId);
    }
}
