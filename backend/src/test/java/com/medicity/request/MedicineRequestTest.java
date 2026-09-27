package com.medicity.request;

import com.jayway.jsonpath.JsonPath;
import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.outbox.OutboxRelay;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
import com.medicity.scheduling.*;
import com.medicity.security.JwtService;
import com.medicity.store.Store;
import com.medicity.store.StoreRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Ask all nearby chemists": who receives a question, what they see, the
 * rules for answering, and how answers are compared.
 */
@AutoConfigureMockMvc
@DisplayName("Asking nearby chemists")
class MedicineRequestTest extends NetworkTestSupport {

    // --- who receives it --------------------------------------------------

    @Test
    @DisplayName("a question goes to every verified store in the radius, and to no one else")
    void questionReachesVerifiedStoresInReach() throws Exception {
        UUID id = ask(3000);

        assertThat(recipients(id)).containsExactlyInAnyOrder("Sri Sai Medicals", "Green Cross", "CityCare");
        queue(nearOwner).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].patientName").value("Meera N."))
                .andExpect(jsonPath("$[0].doctorName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$[0].medicines").value(2));
        queue(farOwner).andExpect(jsonPath("$", hasSize(0)));
        queue(unverifiedOwner).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a store that was not asked cannot read the question, and the attempt is audited")
    void onlyRecipientsCanRead() throws Exception {
        UUID id = ask(3000);

        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(farOwner)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/stores/me/requests/" + id + "/answer").header("Authorization", bearer(farOwner))
                        .contentType(MediaType.APPLICATION_JSON).content(allYes(5.0, 2.0)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND entity_id = ?
                """, Integer.class, id.toString())).isEqualTo(2);

        // Patients cannot reach the store side at all.
        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(meeraUser)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a store sees who prescribed it and what, but not the diagnosis or the patient's full name")
    void storeSeesTheVerifiedPrescription() throws Exception {
        UUID id = ask(3000);

        mvc.perform(get("/api/v1/stores/me/requests/" + id).header("Authorization", bearer(nearOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientName").value("Meera N."))
                .andExpect(jsonPath("$.prescription.doctorName").value("Dr. Anjali Rao"))
                .andExpect(jsonPath("$.prescription.doctorRegistration").value(doctor.getLicenseNumber()))
                .andExpect(jsonPath("$.prescription.diagnosis").doesNotExist())
                .andExpect(jsonPath("$.diagnosis").doesNotExist())
                .andExpect(jsonPath("$.items[?(@.name == '%s')].frequency".formatted(omeprazole.getName()))
                        .value("Once daily before breakfast"))
                // Other brands are offered only where the doctor allowed one.
                .andExpect(jsonPath("$.items[?(@.name == '%s')].equivalents[*].name".formatted(omeprazole.getName()))
                        .value(omez.getName()))
                .andExpect(jsonPath("$.items[?(@.name == '%s')].equivalents[*]".formatted(cetirizine.getName()),
                        hasSize(0)));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'MEDICINE_REQUEST_VIEWED' AND entity_id = ?
                """, Integer.class, id.toString())).isEqualTo(1);
    }

    // --- answering ----------------------------------------------------------

    @Test
    @DisplayName("an answer covers every medicine, respects the doctor's substitution choice, and is given once")
    void answerRules() throws Exception {
        UUID id = ask(3000);

        answer(nearOwner, id, """
                {"lines":[{"medicineId":"%s","availability":"YES","unitPrice":5.5}]}
                """.formatted(omeprazole.getId()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INCOMPLETE_ANSWER"));

        // Cetirizine: the doctor did not allow another brand.
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 5.5, null), line(cetirizine, "YES", null, 2.0, omez)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SUBSTITUTION_NOT_ALLOWED"));

        // Omeprazole may be replaced, but only by the same medicine and strength.
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 5.5, unrelated), line(cetirizine, "NO", null, null, null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_EQUIVALENT"));

        // "Partly" means fewer than asked for.
        answer(nearOwner, id, lines(line(omeprazole, "PARTIAL", 14, 5.5, null), line(cetirizine, "NO", null, null, null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 4.2, omez), line(cetirizine, "PARTIAL", 3, 2.0, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myStatus").value("ANSWERED"))
                .andExpect(jsonPath("$.myAnswer", hasSize(2)));

        answer(nearOwner, id, allYes(5.0, 2.0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ANSWERED"));
    }

    @Test
    @DisplayName("8 tabs of one store answering at once: exactly one answer is kept")
    void concurrentAnswers() throws Exception {
        UUID id = ask(3000);
        List<MedicineRequestService.LineInput> lines = List.of(
                new MedicineRequestService.LineInput(omeprazole.getId(), MedicineRequestService.Availability.YES,
                        null, new BigDecimal("5.50"), null),
                new MedicineRequestService.LineInput(cetirizine.getId(), MedicineRequestService.Availability.NO,
                        null, null, null));

        int wins = race(8, i -> service.answer(nearOwner.getId(), id, null, lines));

        assertThat(wins).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_answer_lines WHERE request_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("once the patient closes a question, stores can no longer answer it or see it queued")
    void closedQuestionsCannotBeAnswered() throws Exception {
        UUID id = ask(3000);
        mvc.perform(post("/api/v1/patients/me/medicine-requests/" + id + "/close")
                        .header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        answer(nearOwner, id, allYes(5.0, 2.0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_CLOSED"));
        queue(nearOwner).andExpect(jsonPath("$", hasSize(0)));
    }

    // --- comparing ------------------------------------------------------------

    @Test
    @DisplayName("answers are ranked: everything first, then cheaper, then nearer; silent stores last")
    void comparisonRanking() throws Exception {
        UUID id = ask(3000);
        // Nearer store: everything, dearer. Near store: everything, cheaper
        // (with the allowed other brand). CityCare does not answer.
        answer(nearerOwner, id, allYes(6.0, 3.0)).andExpect(status().isOk());       // 14*6 + 5*3 = 99
        answer(nearOwner, id, lines(line(omeprazole, "YES", null, 4.2, omez), line(cetirizine, "YES", null, 2.0, null)))
                .andExpect(status().isOk());                                          // 14*4.2 + 5*2 = 68.8

        mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id).header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores", hasSize(3)))
                .andExpect(jsonPath("$.stores[0].name").value("Sri Sai Medicals"))
                .andExpect(jsonPath("$.stores[0].total").value(68.8))
                .andExpect(jsonPath("$.stores[0].cheapestComplete").value(true))
                .andExpect(jsonPath("$.stores[0].lines[?(@.substituteName != null)].substituteName").value(omez.getName()))
                .andExpect(jsonPath("$.stores[1].name").value("Green Cross"))
                .andExpect(jsonPath("$.stores[1].nearestComplete").value(true))
                .andExpect(jsonPath("$.stores[2].name").value("CityCare"))
                .andExpect(jsonPath("$.stores[2].answered").value(false));
    }

    @Test
    @DisplayName("a partial store ranks below every store with everything, even a dearer one")
    void completenessBeatsPrice() {
        var partial = Comparison.StoreAnswer.of(UUID.randomUUID(), "Cheap but short", "", "", 100, true, 3, true, false,
                null, Instant.now(), List.of(line(MedicineRequestService.Availability.PARTIAL, 5, "1.00")), 1);
        var complete = Comparison.StoreAnswer.of(UUID.randomUUID(), "Has it all", "", "", 900, true, 3, true, false,
                null, Instant.now(), List.of(line(MedicineRequestService.Availability.YES, 10, "9.00")), 1);

        assertThat(Comparison.rank(List.of(partial, complete))).extracting(Comparison.StoreAnswer::name)
                .containsExactly("Has it all", "Cheap but short");
    }

    // --- asking rules -----------------------------------------------------------

    @Test
    @DisplayName("one open question per prescription; closing or expiry frees it")
    void oneOpenQuestionPerPrescription() throws Exception {
        UUID first = ask(3000);
        askRaw(3000, meeraUser, prescriptionId)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_ASKED"));

        // Past its lifetime, the old question no longer blocks a new one.
        jdbc.update("UPDATE medicine_requests SET created_at = now() - interval '7 hours', "
                + "expires_at = now() - interval '1 hour' WHERE id = ?", first);
        UUID second = ask(3000);

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT status FROM medicine_requests WHERE id = ?", String.class, first))
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("you can only ask about your own, current prescription")
    void onlyOwnCurrentPrescriptions() throws Exception {
        askRaw(3000, otherUser, prescriptionId).andExpect(status().isNotFound());

        UUID id = ask(3000);
        mvc.perform(get("/api/v1/patients/me/medicine-requests/" + id).header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/patients/me/medicine-requests/" + id + "/close")
                        .header("Authorization", bearer(meeraUser))).andExpect(status().isOk());

        prescribingService.correct(prescriptionId, doctor.getId(), new PrescriptionDraft("Reflux", null, List.of(
                new PrescriptionDraft.Item(omez.getId(), "20mg", "Once daily", 14, 14, false))));
        askRaw(3000, meeraUser, prescriptionId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRESCRIPTION_SUPERSEDED"));
    }

    @Test
    @DisplayName("with no verified store in reach, nothing is sent and nothing is saved")
    void noStoresNearby() throws Exception {
        mvc.perform(post("/api/v1/patients/me/medicine-requests").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"prescriptionId":"%s","latitude":28.6139,"longitude":77.2090,"radiusM":3000}
                                """.formatted(prescriptionId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_STORES_NEARBY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM medicine_requests", Integer.class)).isZero();
    }

    @Test
    @DisplayName("asking tells every store asked; an answer tells the patient")
    void notifications() throws Exception {
        UUID id = ask(3000);
        relay.drain();
        assertThat(notificationsFor(nearOwner, "A patient nearby is asking")).isEqualTo(1);
        assertThat(notificationsFor(farOwner, "A patient nearby is asking")).isZero();

        answer(nearOwner, id, allYes(5.0, 2.0)).andExpect(status().isOk());
        relay.drain();
        assertThat(notificationsFor(meeraUser, "Sri Sai Medicals answered")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND title = ?",
                String.class, meeraUser.getId(), "Sri Sai Medicals answered")).isEqualTo("Has everything you asked for.");
    }

}
