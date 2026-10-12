package com.medicity.clinic;

import com.medicity.patient.Patient;
import com.medicity.queue.QueueService;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The property that makes Medicity sellable to more than one clinic: staff of
 * clinic A can neither read nor change anything of clinic B's. Each case sets
 * up B's data, attacks it as A's receptionist or administrator by id, and then
 * checks the database, not only the response: the row is untouched, and the
 * attempt is in the audit log.
 */
@DisplayName("Clinic isolation")
class ClinicIsolationTest extends ClinicTestBase {

    @Autowired QueueService queue;

    @Test
    @DisplayName("a receptionist's schedule holds only their own clinic's doctors and visits")
    void scheduleIsOwnClinicOnly() throws Exception {
        AppointmentSlot mine = slot(doctorA, Duration.ofHours(3));
        AppointmentSlot theirs = slot(doctorB, Duration.ofHours(3));
        Appointment myVisit = visit(mine, patient("Meera Nair"));
        Appointment theirVisit = visit(theirs, patient("Arjun Mehta"));

        getAs(deskA, "/api/v1/clinics/me/desk/today?date=" + dayOf(mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctors[*].id", hasItem(doctorA.getId().toString())))
                .andExpect(jsonPath("$.doctors[*].id", not(hasItem(doctorB.getId().toString()))))
                .andExpect(jsonPath("$.visits[*].id", hasItem(myVisit.getId().toString())))
                .andExpect(jsonPath("$.visits[*].id", not(hasItem(theirVisit.getId().toString()))))
                .andExpect(jsonPath("$.visits[*].patientName", not(hasItem("Arjun Mehta"))));
    }

    @Test
    @DisplayName("a receptionist cannot mark another clinic's patient arrived, missed or cancelled")
    void cannotChangeOtherClinicsVisit() throws Exception {
        AppointmentSlot soon = slot(doctorB, Duration.ofHours(2));
        AppointmentSlot started = slot(doctorB, Duration.ofHours(-2));
        Appointment upcoming = visit(soon, patient("Arjun Mehta"));
        Appointment late = visit(started, patient("Kavya Reddy"));

        postAs(deskA, "/api/v1/clinics/me/desk/visits/" + upcoming.getId() + "/arrived", null)
                .andExpect(status().isNotFound());
        postAs(deskA, "/api/v1/clinics/me/desk/visits/" + late.getId() + "/no-show", null)
                .andExpect(status().isNotFound());
        postAs(deskA, "/api/v1/clinics/me/desk/visits/" + upcoming.getId() + "/cancel", Map.of("reason", "x"))
                .andExpect(status().isNotFound());

        // Nothing moved.
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, upcoming.getId()))
                .isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT arrived_at IS NULL FROM appointments WHERE id = ?", Boolean.class,
                upcoming.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, late.getId()))
                .isEqualTo("BOOKED");
        // Each attempt was recorded against clinic A, the clinic that made it.
        assertThat(audit("ACCESS_DENIED", upcoming.getId())).isEqualTo(2);
        assertThat(audit("ACCESS_DENIED", late.getId())).isEqualTo(1);
        assertThat(auditForClinic("ACCESS_DENIED", clinicA)).isGreaterThanOrEqualTo(3);

        // And the owner's own desk can.
        postAs(deskB, "/api/v1/clinics/me/desk/visits/" + upcoming.getId() + "/arrived", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a receptionist cannot add a walk-in to another clinic's doctor")
    void cannotQueueOtherClinicsDoctor() throws Exception {
        Patient patient = patient("Arjun Mehta");
        Map<String, Object> body = Map.of("doctorId", doctorB.getId().toString(),
                "patient", Map.of("newPatient", Map.of("fullName", "Walk In", "phone", "9876543210", "ageYears", 30)),
                "reason", "Fever");

        postAs(deskA, "/api/v1/clinics/me/desk/walk-ins", body).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_tokens", Integer.class)).isZero();
        // Refused before anything was recorded: no patient was created for the failed request either.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients WHERE registered_by_clinic_id IS NOT NULL",
                Integer.class)).isZero();
        assertThat(patient.getId()).isNotNull();
        assertThat(audit("ACCESS_DENIED", doctorB.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a receptionist cannot book another clinic's slot")
    void cannotBookOtherClinicsSlot() throws Exception {
        AppointmentSlot theirs = slot(doctorB, Duration.ofHours(5));
        Map<String, Object> body = Map.of("slotId", theirs.getId().toString(),
                "patient", Map.of("newPatient", Map.of("fullName", "Caller", "phone", "9876543210", "ageYears", 40)));

        postAs(deskA, "/api/v1/clinics/me/desk/visits", body).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
        assertThat(audit("ACCESS_DENIED", theirs.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a patient the other clinic's desk recorded cannot be used, or found, by this one")
    void deskPatientsBelongToTheirClinic() throws Exception {
        // Clinic B's desk records a caller.
        String created = postAs(deskB, "/api/v1/clinics/me/desk/patients",
                Map.of("fullName", "Ravi Shankar", "phone", "9876501234", "ageYears", 52))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID theirPatient = UUID.fromString(json.readTree(created).get("patientId").asText());

        // Clinic A cannot find them...
        getAs(deskA, "/api/v1/clinics/me/desk/patients?q=Ravi")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        getAs(deskB, "/api/v1/clinics/me/desk/patients?q=Ravi")
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        // ...nor book or queue them by id.
        AppointmentSlot mine = slot(doctorA, Duration.ofHours(4));
        postAs(deskA, "/api/v1/clinics/me/desk/visits",
                Map.of("slotId", mine.getId().toString(), "patient", Map.of("patientId", theirPatient.toString())))
                .andExpect(status().isNotFound());
        postAs(deskA, "/api/v1/clinics/me/desk/walk-ins",
                Map.of("doctorId", doctorA.getId().toString(), "patient", Map.of("patientId", theirPatient.toString())))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM queue_tokens", Integer.class)).isZero();
        assertThat(audit("ACCESS_DENIED", theirPatient)).isEqualTo(2);
    }

    @Test
    @DisplayName("a clinic administrator's staff list, and everything they manage, is their own clinic's")
    void adminManagesOnlyOwnStaff() throws Exception {
        getAs(adminA, "/api/v1/clinics/me/staff")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].userId", hasItem(deskA.getId().toString())))
                .andExpect(jsonPath("$[*].userId", hasItem(doctorA.getUser().getId().toString())))
                .andExpect(jsonPath("$[*].userId", not(hasItem(deskB.getId().toString()))))
                .andExpect(jsonPath("$[*].userId", not(hasItem(doctorB.getUser().getId().toString()))))
                .andExpect(jsonPath("$[*].userId", not(hasItem(adminB.getId().toString()))));

        String before = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, deskB.getId());
        for (UUID theirs : List.of(deskB.getId(), doctorB.getUser().getId())) {
            postAs(adminA, "/api/v1/clinics/me/staff/" + theirs + "/reset-password", null).andExpect(status().isNotFound());
            postAs(adminA, "/api/v1/clinics/me/staff/" + theirs + "/disable", null).andExpect(status().isNotFound());
            postAs(adminA, "/api/v1/clinics/me/staff/" + theirs + "/enable", null).andExpect(status().isNotFound());
        }

        // B's receptionist is exactly as before, and still gets in.
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, deskB.getId()))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT enabled AND NOT must_change_password FROM users WHERE id = ?",
                Boolean.class, deskB.getId())).isTrue();
        getAs(deskB, "/api/v1/clinics/me").andExpect(status().isOk());
        assertThat(audit("ACCESS_DENIED", deskB.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("the report counts only the administrator's own clinic")
    void reportIsOwnClinicOnly() throws Exception {
        AppointmentSlot mine = slot(doctorA, Duration.ofHours(-3));
        AppointmentSlot theirs = slot(doctorB, Duration.ofHours(-3));
        Appointment mineVisit = visit(mine, patient("Meera Nair"));
        mineVisit.setStatus(AppointmentStatus.COMPLETED);
        appointmentRepository.saveAndFlush(mineVisit);
        // Three of B's: seen, missed and cancelled.
        for (AppointmentStatus s : List.of(AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW)) {
            AppointmentSlot extra = slot(doctorB, Duration.ofHours(-5).minusMinutes(60L * s.ordinal()));
            Appointment v = visit(extra, patient("Patient " + s));
            v.setStatus(s);
            appointmentRepository.saveAndFlush(v);
        }
        Appointment cancelled = visit(theirs, patient("Cancelled One"));
        cancelled.cancel(clock.instant(), "changed mind");
        appointmentRepository.saveAndFlush(cancelled);

        String day = dayOf(mine).toString();
        String from = dayOf(mine).minusDays(1).toString();
        String to = dayOf(mine).plusDays(1).toString();
        String body = getAs(adminA, "/api/v1/clinics/me/report?from=" + from + "&to=" + to)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).at("/totals/completed").asInt()).isEqualTo(1);
        assertThat(json.readTree(body).at("/totals/noShows").asInt()).isZero();
        assertThat(json.readTree(body).at("/totals/cancellations").asInt()).isZero();
        assertThat(day).isNotBlank();

        String theirBody = getAs(adminB, "/api/v1/clinics/me/report?from=" + from + "&to=" + to)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(theirBody).at("/totals/completed").asInt()).isEqualTo(1);
        assertThat(json.readTree(theirBody).at("/totals/noShows").asInt()).isEqualTo(1);
        assertThat(json.readTree(theirBody).at("/totals/cancellations").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("walk-ins on the desk are the clinic's own doctors' only")
    void walkInLinesAreOwnClinicOnly() throws Exception {
        // B's doctor has a walk-in waiting; A's desk must not see the line.
        Patient theirs = patient("Waiting At B");
        queue.join(doctorB.getId(), theirs, "Cough");
        Patient mine = patient("Waiting At A");
        queue.join(doctorA.getId(), mine, "Fever");

        getAs(deskA, "/api/v1/clinics/me/desk/today")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walkIns", hasSize(1)))
                .andExpect(jsonPath("$.walkIns[0].patientName").value("Waiting At A"));
        getAs(deskB, "/api/v1/clinics/me/desk/today")
                .andExpect(jsonPath("$.walkIns", hasSize(1)))
                .andExpect(jsonPath("$.walkIns[0].patientName").value("Waiting At B"));
    }

    @Test
    @DisplayName("roles are not interchangeable: a receptionist is not an administrator, a patient or doctor is neither")
    void rolesAreSeparate() throws Exception {
        User patientUser = patient("Meera Nair").getUser();
        for (String path : List.of("/api/v1/clinics/me/staff", "/api/v1/clinics/me/report?from=2030-01-01&to=2030-01-02")) {
            getAs(deskA, path).andExpect(status().isForbidden());
            getAs(patientUser, path).andExpect(status().isForbidden());
            getAs(doctorA.getUser(), path).andExpect(status().isForbidden());
        }
        postAs(deskA, "/api/v1/clinics/me/receptionists",
                Map.of("fullName", "New Person", "email", "new.person@test.example")).andExpect(status().isForbidden());
        putAs(deskA, "/api/v1/clinics/me", Map.of("name", "Takeover")).andExpect(status().isForbidden());
        getAs(patientUser, "/api/v1/clinics/me/desk/today").andExpect(status().isForbidden());
        getAs(doctorA.getUser(), "/api/v1/clinics/me/desk/today").andExpect(status().isForbidden());
        // Signed out is a 401, not a 403.
        getAs(null, "/api/v1/clinics/me").andExpect(status().isUnauthorized());
        getAs(null, "/api/v1/clinics/me/desk/today").andExpect(status().isUnauthorized());
        // And only the platform administrator creates clinics.
        postAs(adminA, "/api/v1/admin/clinics", Map.of("clinic", Map.of("name", "Sneaky Clinic"),
                "adminFullName", "Sneaky", "adminEmail", "sneaky@test.example")).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinics", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a deactivated clinic's staff cannot work")
    void inactiveClinicIsShutOut() throws Exception {
        jdbc.update("UPDATE clinics SET active = FALSE WHERE id = ?", clinicA.getId());

        getAs(deskA, "/api/v1/clinics/me/desk/today").andExpect(status().isForbidden());
        getAs(adminA, "/api/v1/clinics/me").andExpect(status().isForbidden());
        // Clinic B is unaffected.
        getAs(deskB, "/api/v1/clinics/me/desk/today").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a staff account with no clinic (or whose role no longer matches its clinic row) is refused")
    void staffWithoutAClinicIsRefused() throws Exception {
        User stray = user("Stray Receptionist", com.medicity.user.Role.RECEPTIONIST);
        getAs(stray, "/api/v1/clinics/me/desk/today").andExpect(status().isForbidden());

        // The clinic row says administrator but the account is a receptionist: neither is trusted.
        jdbc.update("UPDATE clinic_staff SET staff_role = 'CLINIC_ADMIN' WHERE user_id = ?", deskA.getId());
        getAs(deskA, "/api/v1/clinics/me/desk/today").andExpect(status().isForbidden());
    }
}
