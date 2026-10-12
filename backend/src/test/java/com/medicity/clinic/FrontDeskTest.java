package com.medicity.clinic;

import com.fasterxml.jackson.databind.JsonNode;
import com.medicity.doctor.Doctor;
import com.medicity.patient.Patient;
import com.medicity.queue.QueueService;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A clinic's front desk doing its job: the day's schedule, arrivals, missed
 * visits, walk-in tokens, and booking or cancelling for a caller. Whether it
 * can reach another clinic's data is {@link ClinicIsolationTest}'s question.
 */
@DisplayName("Clinic front desk")
class FrontDeskTest extends ClinicTestBase {

    @Autowired QueueService queue;

    private static final String DESK = "/api/v1/clinics/me/desk";

    private JsonNode read(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> caller(String name, String phone) {
        return Map.of("newPatient", Map.of("fullName", name, "phone", phone, "ageYears", 41, "gender", "FEMALE"));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("today lists the clinic's doctors with their walk-in lines, and the day's visits with patients and phones")
    void today() throws Exception {
        AppointmentSlot slot = slot(doctorA, Duration.ofHours(2));
        Patient meera = patient("Meera Nair");
        jdbc.update("UPDATE users SET phone = '+919876500101' WHERE id = ?", meera.getUser().getId());
        Appointment visit = visit(slot, meera);
        queue.join(doctorA.getId(), patient("Arjun Mehta"), "Fever");
        jdbc.update("INSERT INTO queue_days (doctor_id, queue_date) VALUES (?, ?) ON CONFLICT DO NOTHING",
                doctorA.getId(), java.sql.Date.valueOf(java.time.LocalDate.now(clock.withZone(INDIA))));

        getAs(deskA, DESK + "/today?date=" + dayOf(slot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.doctors", hasSize(1)))
                .andExpect(jsonPath("$.doctors[0].fullName").value("Dr. Alder"))
                .andExpect(jsonPath("$.doctors[0].waiting").value(1))
                .andExpect(jsonPath("$.doctors[0].walkInsOpen").value(true))
                .andExpect(jsonPath("$.visits", hasSize(1)))
                .andExpect(jsonPath("$.visits[0].id").value(visit.getId().toString()))
                .andExpect(jsonPath("$.visits[0].patientName").value("Meera Nair"))
                .andExpect(jsonPath("$.visits[0].patientPhone").value("+919876500101"))
                .andExpect(jsonPath("$.visits[0].status").value("BOOKED"))
                .andExpect(jsonPath("$.walkIns", hasSize(1)))
                .andExpect(jsonPath("$.walkIns[0].tokenNo").value(101));

        // A day with nothing on it is empty, not an error.
        getAs(deskA, DESK + "/today?date=" + dayOf(slot).plusDays(3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visits", hasSize(0)));
        // The clinic administrator may use the desk too.
        getAs(adminA, DESK + "/today").andExpect(status().isOk());
    }

    @Test
    @DisplayName("marking arrival is recorded, audited and harmless to repeat; only a booked, current visit can arrive")
    void arrival() throws Exception {
        Appointment visit = visit(slot(doctorA, Duration.ofHours(1)), patient("Meera Nair"));

        postAs(deskA, DESK + "/visits/" + visit.getId() + "/arrived", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAt").isNotEmpty())
                .andExpect(jsonPath("$.status").value("BOOKED"));
        postAs(deskA, DESK + "/visits/" + visit.getId() + "/arrived", null).andExpect(status().isOk());
        assertThat(audit("CLINIC_VISIT_ARRIVED", visit.getId())).isEqualTo(1);

        Appointment cancelled = visit(slot(doctorA, Duration.ofHours(3)), patient("Kavya Reddy"));
        cancelled.cancel(clock.instant(), "unwell");
        appointmentRepository.saveAndFlush(cancelled);
        postAs(deskA, DESK + "/visits/" + cancelled.getId() + "/arrived", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_VISIT_STATE"));

        Appointment nextWeek = visit(slot(doctorA, Duration.ofDays(5)), patient("Arjun Mehta"));
        postAs(deskA, DESK + "/visits/" + nextWeek.getId() + "/arrived", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_TODAY"));
        assertThat(count("SELECT count(*) FROM appointments WHERE arrived_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("a visit whose time has passed can be marked missed; one that has not started, or whose patient arrived, cannot")
    void noShow() throws Exception {
        Appointment missed = visit(slot(doctorA, Duration.ofHours(-2)), patient("Meera Nair"));
        Appointment upcoming = visit(slot(doctorA, Duration.ofHours(2)), patient("Kavya Reddy"));
        Appointment here = visit(slot(doctorA, Duration.ofHours(-4)), patient("Arjun Mehta"));
        postAs(deskA, DESK + "/visits/" + here.getId() + "/arrived", null).andExpect(status().isOk());

        postAs(deskA, DESK + "/visits/" + missed.getId() + "/no-show", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
        assertThat(audit("CLINIC_VISIT_NO_SHOW", missed.getId())).isEqualTo(1);
        // Who did it, and for which clinic, is in the audit row.
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'CLINIC_VISIT_NO_SHOW' AND entity_id = ?",
                UUID.class, missed.getId().toString())).isEqualTo(deskA.getId());

        postAs(deskA, DESK + "/visits/" + upcoming.getId() + "/no-show", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VISIT_NOT_STARTED"));
        postAs(deskA, DESK + "/visits/" + here.getId() + "/no-show", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ALREADY_ARRIVED"));
        postAs(deskA, DESK + "/visits/" + missed.getId() + "/no-show", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_VISIT_STATE"));
        assertThat(count("SELECT count(*) FROM appointments WHERE status = 'NO_SHOW'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a walk-in is added to a doctor's ordinary queue for someone with no account, who is recorded for this clinic only")
    void walkInForANewPatient() throws Exception {
        JsonNode first = read(postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", caller("Lakshmi Iyer", "9876512345"), "reason", "Cough")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenNo").value(101))
                .andExpect(jsonPath("$.patientName").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.status").value("WAITING")));
        UUID token = UUID.fromString(first.get("id").asText());

        // The person is an ordinary patient row behind an account nobody can sign in to.
        assertThat(jdbc.queryForObject("""
                SELECT u.enabled FROM patients p JOIN users u ON u.id = p.user_id WHERE p.registered_by_clinic_id = ?
                """, Boolean.class, clinicA.getId())).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT u.email LIKE '%@walkin.invalid' FROM patients p JOIN users u ON u.id = p.user_id
                WHERE p.registered_by_clinic_id = ?
                """, Boolean.class, clinicA.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT added_by_user_id FROM queue_tokens WHERE id = ?", UUID.class, token))
                .isEqualTo(deskA.getId());
        assertThat(audit("QUEUE_JOINED", token)).isEqualTo(1);
        assertThat(audit("CLINIC_WALK_IN_ADDED", token)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail ->> 'addedByUserId' FROM audit_log WHERE action = 'QUEUE_JOINED' AND entity_id = ?",
                String.class, token.toString())).isEqualTo(deskA.getId().toString());

        // Same caller, same name, another doctor of the clinic: the same record, the next number on that doctor's line.
        Doctor second = doctor(clinicA, "Dr. Second", "ENT");
        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", second.getId().toString(),
                "patient", caller("lakshmi iyer", "9876512345")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tokenNo").value(101));
        assertThat(count("SELECT count(*) FROM patients WHERE registered_by_clinic_id IS NOT NULL")).isEqualTo(1);

        // Twice in one doctor's line is refused, as it is for a patient on their phone.
        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", caller("Lakshmi Iyer", "9876512345")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_IN_QUEUE"));

        // The doctor's own front desk sees the token like any other.
        assertThat(queue.desk(doctorA.getId())).extracting(QueueService.Token::patientName).containsExactly("Lakshmi Iyer");
    }

    @Test
    @DisplayName("a walk-in can be for a Medicity account, named by its email; the request must name exactly one patient")
    void walkInForAnAccount() throws Exception {
        Patient meera = patient("Meera Nair");
        String email = meera.getUser().getEmail();

        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", Map.of("email", email.toUpperCase())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.patientName").value("Meera Nair"));
        assertThat(count("SELECT count(*) FROM queue_tokens WHERE patient_id = ?", meera.getId())).isEqualTo(1);

        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", Map.of("email", "nobody@test.example")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(), "patient", Map.of()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PATIENT_REQUIRED"));
        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", Map.of("email", email, "newPatient", Map.of("fullName", "Two", "ageYears", 5))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PATIENT_REQUIRED"));
        // A doctor's or another receptionist's email is not a patient account.
        postAs(deskA, DESK + "/walk-ins", Map.of("doctorId", doctorA.getId().toString(),
                "patient", Map.of("email", deskB.getEmail())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("booking for a caller takes the slot like any booking, remembers who did it, and a refused booking leaves no trace")
    void bookForACaller() throws Exception {
        AppointmentSlot slot = slot(doctorA, Duration.ofHours(4));

        JsonNode booked = read(postAs(deskA, DESK + "/visits", Map.of("slotId", slot.getId().toString(),
                "patient", caller("Ravi Shankar", "9876500000"), "reason", "Back pain"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.patientName").value("Ravi Shankar"))
                .andExpect(jsonPath("$.bookedByDesk").value(true)));
        UUID id = UUID.fromString(booked.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT booked_by_user_id FROM appointments WHERE id = ?", UUID.class, id))
                .isEqualTo(deskA.getId());
        assertThat(audit("APPOINTMENT_BOOKED", id)).isEqualTo(1);
        assertThat(audit("CLINIC_VISIT_BOOKED", id)).isEqualTo(1);
        getAs(deskA, DESK + "/today?date=" + dayOf(slot)).andExpect(jsonPath("$.visits[*].id").value(hasItem(id.toString())));

        // The slot is taken: the next caller loses, and the person recorded for the failed request is not kept.
        int before = count("SELECT count(*) FROM patients WHERE registered_by_clinic_id IS NOT NULL");
        postAs(deskA, DESK + "/visits", Map.of("slotId", slot.getId().toString(),
                "patient", caller("Someone Else", "9876511111")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_ALREADY_BOOKED"));
        // So is a time too close to now, which the booking service refuses the same way for everyone.
        AppointmentSlot tooSoon = slot(doctorA, Duration.ofMinutes(10));
        postAs(deskA, DESK + "/visits", Map.of("slotId", tooSoon.getId().toString(),
                "patient", caller("Someone Else", "9876511111")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SLOT_TOO_SOON"));
        assertThat(count("SELECT count(*) FROM patients WHERE registered_by_clinic_id IS NOT NULL")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM appointments")).isEqualTo(1);
    }

    @Test
    @DisplayName("booking for an existing patient account (by email) puts the visit in that patient's own list")
    void bookForAnAccount() throws Exception {
        Patient meera = patient("Meera Nair");
        AppointmentSlot slot = slot(doctorA, Duration.ofHours(4));

        postAs(deskA, DESK + "/visits", Map.of("slotId", slot.getId().toString(),
                "patient", Map.of("email", meera.getUser().getEmail())))
                .andExpect(status().isCreated());

        getAs(meera.getUser(), "/api/v1/patients/me/appointments?scope=upcoming")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].doctorName").value("Dr. Alder"));
        // The patient hears about it, as with any booking.
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'APPOINTMENT_BOOKED'"
                + " AND payload ->> 'patientUserId' = ?", meera.getUser().getId().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling for a caller frees the slot, tells the patient, is audited, and repeating it changes nothing")
    void cancelForACaller() throws Exception {
        AppointmentSlot slot = slot(doctorA, Duration.ofHours(4));
        Patient meera = patient("Meera Nair");
        UUID id = UUID.fromString(read(postAs(deskA, DESK + "/visits", Map.of("slotId", slot.getId().toString(),
                "patient", Map.of("email", meera.getUser().getEmail()))).andExpect(status().isCreated())).get("id").asText());

        postAs(deskA, DESK + "/visits/" + id + "/cancel", Map.of("reason", "Patient called to cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        postAs(deskA, DESK + "/visits/" + id + "/cancel", Map.of()).andExpect(status().isOk());

        assertThat(audit("CLINIC_VISIT_CANCELLED", id)).isEqualTo(2);    // each request by staff is its own row
        assertThat(audit("APPOINTMENT_CANCELLED", id)).isEqualTo(1);     // the cancellation itself happened once
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM appointments WHERE id = ?", String.class, id))
                .isEqualTo("Patient called to cancel");
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'APPOINTMENT_CANCELLED' AND aggregate_id = ?", id))
                .isEqualTo(1);
        // The slot can be booked again.
        postAs(deskA, DESK + "/visits", Map.of("slotId", slot.getId().toString(), "patient", caller("Next Caller", "9876522222")))
                .andExpect(status().isCreated());
        // A completed visit cannot be cancelled.
        Appointment done = visit(slot(doctorA, Duration.ofHours(-6)), patient("Kavya Reddy"));
        done.setStatus(AppointmentStatus.COMPLETED);
        appointmentRepository.saveAndFlush(done);
        postAs(deskA, DESK + "/visits/" + done.getId() + "/cancel", Map.of())
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("finding a patient: recorded callers by name or phone (search characters are literal), accounts by whole email; searches are audited")
    void searchPatients() throws Exception {
        postAs(deskA, DESK + "/patients", Map.of("fullName", "Ravi Shankar", "phone", "9876500000", "ageYears", 52))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("CLINIC"));
        // Recording the same caller twice is one record.
        postAs(deskA, DESK + "/patients", Map.of("fullName", "Ravi Shankar", "phone", "9876500000", "ageYears", 52))
                .andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM patients WHERE registered_by_clinic_id = ?", clinicA.getId())).isEqualTo(1);
        Patient meera = patient("Meera Nair");

        getAs(deskA, DESK + "/patients?q=shank").andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].fullName").value("Ravi Shankar"));
        getAs(deskA, DESK + "/patients?q=98765").andExpect(jsonPath("$", hasSize(1)));
        getAs(deskA, DESK + "/patients?q=%25%25").andExpect(jsonPath("$", hasSize(0)));       // "%%" is text, not "everyone"
        getAs(deskA, DESK + "/patients?q=r").andExpect(jsonPath("$", hasSize(0)));            // too short to list anyone
        getAs(deskA, DESK + "/patients?q=" + meera.getUser().getEmail())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].source").value("ACCOUNT"))
                .andExpect(jsonPath("$[0].email").value(meera.getUser().getEmail()))
                .andExpect(jsonPath("$[0].patientId").doesNotExist());
        // A name alone does not reveal Medicity's accounts.
        getAs(deskA, DESK + "/patients?q=Meera").andExpect(jsonPath("$", hasSize(0)));
        assertThat(auditForClinic("CLINIC_PATIENT_SEARCHED", clinicA)).isEqualTo(5);
        assertThat(auditForClinic("CLINIC_PATIENT_REGISTERED", clinicA)).isEqualTo(2);
    }

    @Test
    @DisplayName("invalid requests are refused before anything is recorded")
    void validation() throws Exception {
        postAs(deskA, DESK + "/patients", Map.of("fullName", " ", "ageYears", 20)).andExpect(status().isBadRequest());
        postAs(deskA, DESK + "/patients", Map.of("fullName", "X", "phone", "12", "ageYears", 20)).andExpect(status().isBadRequest());
        postAs(deskA, DESK + "/patients", Map.of("fullName", "X", "ageYears", 200)).andExpect(status().isBadRequest());
        postAs(deskA, DESK + "/walk-ins", Map.of("patient", Map.of())).andExpect(status().isBadRequest());
        postAs(deskA, DESK + "/visits", Map.of("patient", Map.of())).andExpect(status().isBadRequest());
        getAs(deskA, DESK + "/today?date=yesterday").andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM patients WHERE registered_by_clinic_id IS NOT NULL")).isZero();
    }
}
