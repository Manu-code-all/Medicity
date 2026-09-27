package com.medicity.patient;

import com.jayway.jsonpath.JsonPath;
import com.medicity.clinical.PrescribingService.PrescriptionDraft;
import com.medicity.request.NetworkTestSupport;
import com.medicity.scheduling.AppointmentSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Family members managed from one account, and who may act for whom. */
@AutoConfigureMockMvc
@DisplayName("Family members")
class FamilyTest extends NetworkTestSupport {

    @Test
    @DisplayName("an account adds a family member, who then appears in its family with no sign-in of their own")
    void addFamilyMember() throws Exception {
        UUID aarav = addMember("Aarav Nair", "CHILD", "2019-06-02");

        mvc.perform(get("/api/v1/patients/me/family").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].self").value(true))
                .andExpect(jsonPath("$[0].fullName").value("Meera Nair"))
                .andExpect(jsonPath("$[1].patientId").value(aarav.toString()))
                .andExpect(jsonPath("$[1].relationship").value("CHILD"));

        // The portal, acting for him: his name, the account's contact details.
        asFor(get("/api/v1/patients/me"), meeraUser, aarav)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Aarav Nair"))
                .andExpect(jsonPath("$.relationship").value("CHILD"))
                .andExpect(jsonPath("$.email").value(meeraUser.getEmail()));
    }

    @Test
    @DisplayName("booking for a family member books for them, notifies the account, and the account can cancel it")
    void bookForFamilyMember() throws Exception {
        UUID aarav = addMember("Aarav Nair", "CHILD", "2019-06-02");
        AppointmentSlot slot = futureSlot();

        String body = asFor(post("/api/v1/appointments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"" + slot.getId() + "\",\"reason\":\"Fever\"}"), meeraUser, aarav)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId").value(aarav.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID appointment = UUID.fromString(JsonPath.read(body, "$.id"));

        asFor(get("/api/v1/patients/me/appointments"), meeraUser, aarav)
                .andExpect(jsonPath("$.content", hasSize(1)));
        mvc.perform(get("/api/v1/patients/me/appointments").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$.content", hasSize(0)));

        relay.drain();
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND kind = 'APPOINTMENT_BOOKED'",
                String.class, meeraUser.getId())).isEqualTo("For Aarav, with Dr. Anjali Rao.");

        mvc.perform(post("/api/v1/appointments/" + appointment + "/cancel").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Better now\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("nobody can act for someone else's family member: 404, like an id that does not exist, and audited")
    void cannotActForAnotherFamily() throws Exception {
        UUID aarav = addMember("Aarav Nair", "CHILD", "2019-06-02");

        asFor(get("/api/v1/patients/me/prescriptions"), otherUser, aarav).andExpect(status().isNotFound());
        asFor(get("/api/v1/patients/me/prescriptions"), otherUser, UUID.randomUUID()).andExpect(status().isNotFound());
        // Nor act for another account holder by their patient id.
        UUID meeraPatient = jdbc.queryForObject("SELECT id FROM patients WHERE user_id = ?", UUID.class, meeraUser.getId());
        asFor(get("/api/v1/patients/me/prescriptions"), otherUser, meeraPatient).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND entity_id = ?",
                Integer.class, aarav.toString())).isEqualTo(1);

        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(otherUser))
                        .header(ActingPatient.HEADER, "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the same Idempotency-Key and body for another family member is a different request, not a replay")
    void idempotencyKeyIncludesThePatient() throws Exception {
        UUID aarav = addMember("Aarav Nair", "CHILD", "2019-06-02");
        AppointmentSlot slot = futureSlot();
        String body = "{\"slotId\":\"" + slot.getId() + "\",\"reason\":\"Check-up\"}";
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/appointments").header("Authorization", bearer(meeraUser))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        asFor(post("/api/v1/appointments").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body), meeraUser, aarav)
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a family member's prescription goes to the chemists; the store sees their name, not the account's")
    void askChemistsForFamilyMember() throws Exception {
        UUID aarav = addMember("Aarav Nair", "CHILD", "2019-06-02");
        Patient aaravPatient = patientRepository.findById(aarav).orElseThrow();
        UUID rx = prescribe(aaravPatient, List.of(
                new PrescriptionDraft.Item(cetirizine.getId(), "5mg", "At night", 5, 5, false)));

        String body = asFor(post("/api/v1/patients/me/medicine-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prescriptionId\":\"%s\",\"latitude\":%s,\"longitude\":%s,\"radiusM\":3000}"
                                .formatted(rx, LAT, LNG)), meeraUser, aarav)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        queue(nearOwner).andExpect(jsonPath("$[0].patientName").value("Aarav N."));
        // Acting as herself, Meera cannot send her son's prescription as her own.
        askRaw(3000, meeraUser, rx).andExpect(status().isNotFound());
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an account manages at most 8 family members")
    void familyIsCapped() throws Exception {
        for (int i = 0; i < 8; i++) {
            addMember("Member " + i, "OTHER", "1990-01-01");
        }
        mvc.perform(post("/api/v1/patients/me/family").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberJson("One Too Many", "OTHER", "1990-01-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAMILY_FULL"));
    }

    // --- helpers ------------------------------------------------------------------

    private UUID addMember(String name, String relationship, String dateOfBirth) throws Exception {
        String body = mvc.perform(post("/api/v1/patients/me/family").header("Authorization", bearer(meeraUser))
                        .contentType(MediaType.APPLICATION_JSON).content(memberJson(name, relationship, dateOfBirth)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.patientId"));
    }

    private static String memberJson(String name, String relationship, String dateOfBirth) {
        return "{\"fullName\":\"%s\",\"relationship\":\"%s\",\"dateOfBirth\":\"%s\",\"gender\":\"MALE\"}"
                .formatted(name, relationship, dateOfBirth);
    }

    private ResultActions asFor(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                com.medicity.user.User account, UUID patientId) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(account)).header(ActingPatient.HEADER, patientId));
    }

    private AppointmentSlot futureSlot() {
        Instant start = clock.instant().truncatedTo(ChronoUnit.HOURS).plus(3, ChronoUnit.DAYS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
    }
}
