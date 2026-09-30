package com.medicity.request;

import com.jayway.jsonpath.JsonPath;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.patient.Patient;
import com.medicity.pharmacy.Medicine;
import com.medicity.user.Role;
import com.medicity.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Live stock that answers questions by itself, and what a store learns from its questions. */
@AutoConfigureMockMvc
@DisplayName("Store stock and insights")
class StoreStockAndInsightsTest extends NetworkTestSupport {

    @Autowired ReservationService reservations;

    // --- live stock ---------------------------------------------------------

    @Test
    @DisplayName("a store with fresh stock and automatic answers answers the moment it is asked")
    void answersFromStock() throws Exception {
        putStock(nearOwner, stock(omeprazole, 20, "4.00"), stock(cetirizine, 2, "1.50")).andExpect(status().isOk());
        autoAnswer(nearOwner, true);

        UUID id = ask(3000);

        String body = mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id)
                        .header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.stores[?(@.name == 'Sri Sai Medicals')].automatic").value(true))
                .andExpect(jsonPath("$.stores[?(@.name == 'Green Cross')].answered").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> availability = JsonPath.read(body,
                "$.stores[?(@.name == 'Sri Sai Medicals')].lines[*].availability");
        assertThat(availability).containsExactlyInAnyOrder("YES", "PARTIAL");
        List<Integer> partial = JsonPath.read(body,
                "$.stores[?(@.name == 'Sri Sai Medicals')].lines[?(@.availability == 'PARTIAL')].quantityAvailable");
        assertThat(partial).containsExactly(2);
    }

    @Test
    @DisplayName("reserved medicines are not offered again: held or collected since the list, they are off the shelf")
    void reservationsTakeFromStock() throws Exception {
        // Exactly what one prescription asks for: 14 omeprazole, 5 cetirizine.
        putStock(nearOwner, stock(omeprazole, 14, "4.00"), stock(cetirizine, 5, "1.50")).andExpect(status().isOk());
        autoAnswer(nearOwner, true);
        List<PrescriptionDraft.Item> same = List.of(
                new PrescriptionDraft.Item(omeprazole.getId(), "20mg", "Once daily before breakfast", 14, 14, false),
                new PrescriptionDraft.Item(cetirizine.getId(), "10mg", "At night", 5, 5, false));

        UUID first = ask(3000);
        assertThat(answerAt(first)).isEqualTo("YES,YES");
        UUID held = reservations.reserve(meeraUser.getId(), first, near.getId());

        // Held: all of it is set aside for Meera, so the next question hears "no".
        UUID second = askAbout(prescribe(meera, same));
        assertThat(answerAt(second)).isEqualTo("NO,NO");

        // Cancelled: back on the shelf, without anyone writing to the stock.
        reservations.cancel(meeraUser.getId(), held);
        UUID third = askAbout(prescribe(meera, same));
        assertThat(answerAt(third)).isEqualTo("YES,YES");

        // Collected: gone until the billing software sends the next list.
        UUID taken = reservations.reserve(meeraUser.getId(), third, near.getId());
        reservations.collect(nearOwner.getId(), taken,
                jdbc.queryForObject("SELECT pickup_code FROM reservations WHERE id = ?", String.class, taken));
        assertThat(answerAt(askAbout(prescribe(meera, same)))).isEqualTo("NO,NO");
        putStock(nearOwner, stock(omeprazole, 14, "4.00"), stock(cetirizine, 5, "1.50")).andExpect(status().isOk());
        assertThat(answerAt(askAbout(prescribe(meera, same)))).isEqualTo("YES,YES");
    }

    @Test
    @DisplayName("from stock, another brand is offered only where the doctor allowed it")
    void substitutesFromStockOnlyWhereAllowed() throws Exception {
        // No omeprazole, but its other brand; cetirizine's other brand is not allowed.
        putStock(nearOwner, stock(omez, 50, "3.00")).andExpect(status().isOk());
        autoAnswer(nearOwner, true);

        UUID id = ask(3000);

        String lines = jdbc.queryForObject("""
                SELECT string_agg(availability || ':' || coalesce(substitute_medicine_id::text, '-'), ',' ORDER BY availability)
                FROM request_answer_lines WHERE request_id = ? AND store_id = ?
                """, String.class, id, near.getId());
        assertThat(lines).isEqualTo("NO:-,YES:" + omez.getId());
    }

    @Test
    @DisplayName("stale stock, or automatic answers off, leaves the question for the chemist")
    void staleOrOffAnswersByHand() throws Exception {
        putStock(nearOwner, stock(omeprazole, 20, "4.00"), stock(cetirizine, 20, "1.50"));
        autoAnswer(nearOwner, true);
        jdbc.update("UPDATE stores SET stock_updated_at = now() - interval '25 hours' WHERE id = ?", near.getId());
        putStock(nearerOwner, stock(omeprazole, 20, "4.00"), stock(cetirizine, 20, "1.50"));
        autoAnswer(nearerOwner, false);

        UUID id = ask(3000);

        assertThat(jdbc.queryForList("SELECT status FROM request_recipients WHERE request_id = ? AND store_id IN (?, ?)",
                String.class, id, near.getId(), nearer.getId())).containsOnly("PENDING");
        mvc.perform(get("/api/v1/stores/me/stock").header("Authorization", bearer(nearOwner)))
                .andExpect(jsonPath("$.fresh").value(false))
                .andExpect(jsonPath("$.autoAnswer").value(true));
    }

    @Test
    @DisplayName("the stock list is replaced whole: what is missing is out of stock; unknown medicines are refused")
    void stockIsReplacedWhole() throws Exception {
        putStock(nearOwner, stock(omeprazole, 20, "4.00"), stock(cetirizine, 5, "1.50")).andExpect(status().isOk());
        putStock(nearOwner, stock(omez, 7, "3.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].medicineId").value(omez.getId().toString()));

        mvc.perform(put("/api/v1/stores/me/stock").header("Authorization", bearer(nearOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + stock(UUID.randomUUID(), 1, "1.00") + "]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_MEDICINE"));
        putStock(nearOwner, stock(omez, 1, "1.00"), stock(omez, 2, "1.00"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DUPLICATE_MEDICINE"));

        // A patient has no stock.
        mvc.perform(get("/api/v1/stores/me/stock").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isForbidden());
    }

    // --- insights -----------------------------------------------------------------

    @Test
    @DisplayName("insights count what this store was asked and said; one person's medicine stays hidden")
    void insights() throws Exception {
        User kavyaUser = persistUser(Role.PATIENT, "Kavya Reddy");
        Patient kavya = persistPatient(kavyaUser);
        UUID kavyaRx = prescribe(kavya, List.of(
                new PrescriptionDraft.Item(cetirizine.getId(), "10mg", "At night", 5, 5, false)));

        UUID meeraQuestion = ask(3000);
        String body = mvc.perform(post("/api/v1/patients/me/medicine-requests").header("Authorization", bearer(kavyaUser))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"prescriptionId":"%s","latitude":%s,"longitude":%s,"radiusM":3000}
                                """.formatted(kavyaRx, LAT, LNG)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID kavyaQuestion = UUID.fromString(JsonPath.read(body, "$.id"));

        answer(nearOwner, meeraQuestion, lines(line(omeprazole, "YES", null, 5.0, null),
                line(cetirizine, "NO", null, null, null))).andExpect(status().isOk());
        answer(nearOwner, kavyaQuestion, lines(line(cetirizine, "NO", null, null, null))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/stores/me/insights").header("Authorization", bearer(nearOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.questionsReceived").value(2))
                .andExpect(jsonPath("$.summary.answered").value(2))
                // Omeprazole: only Meera asked, so it is not listed.
                .andExpect(jsonPath("$.medicines", hasSize(1)))
                .andExpect(jsonPath("$.medicines[0].name").value(cetirizine.getName()))
                .andExpect(jsonPath("$.medicines[0].patients").value(2))
                .andExpect(jsonPath("$.medicines[0].saidNo").value(2))
                .andExpect(jsonPath("$.medicines[0].considerStocking").value(true));

        // A store that was never asked learns nothing.
        mvc.perform(get("/api/v1/stores/me/insights").header("Authorization", bearer(farOwner)))
                .andExpect(jsonPath("$.summary.questionsReceived").value(0))
                .andExpect(jsonPath("$.medicines", hasSize(0)));
    }

    // --- helpers ------------------------------------------------------------------

    private UUID askAbout(UUID prescription) throws Exception {
        String body = askRaw(3000, meeraUser, prescription).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** The near store's automatic answer, one availability per medicine. */
    private String answerAt(UUID requestId) {
        return jdbc.queryForObject("""
                SELECT string_agg(availability, ',' ORDER BY availability) FROM request_answer_lines
                WHERE request_id = ? AND store_id = ?
                """, String.class, requestId, near.getId());
    }

    private ResultActions putStock(User owner, String... items) throws Exception {
        return mvc.perform(put("/api/v1/stores/me/stock").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + String.join(",", items) + "]}"));
    }

    private void autoAnswer(User owner, boolean enabled) throws Exception {
        mvc.perform(put("/api/v1/stores/me/auto-answer").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk());
    }

    private static String stock(Medicine m, int quantity, String price) {
        return stock(m.getId(), quantity, price);
    }

    private static String stock(UUID medicineId, int quantity, String price) {
        return "{\"medicineId\":\"%s\",\"quantity\":%d,\"unitPrice\":%s}".formatted(medicineId, quantity, price);
    }
}
