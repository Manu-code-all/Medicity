package com.medicity.scan;

import com.jayway.jsonpath.JsonPath;
import com.medicity.doctor.Doctor;
import com.medicity.request.NetworkTestSupport;
import com.medicity.scan.PrescriptionReader.ReadLine;
import com.medicity.scan.PrescriptionReader.Reading;
import com.medicity.scheduling.Appointment;
import com.medicity.scheduling.AppointmentSlot;
import com.medicity.scheduling.AppointmentStatus;
import com.medicity.user.Role;
import com.medicity.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The photographed handwritten prescription: upload, a draft read from it,
 * and who may see the original. The reader is a stub: what matters here is
 * what the application does with a reading, and that a reading alone never
 * becomes a prescription.
 */
@AutoConfigureMockMvc
@DisplayName("Handwritten prescription photos")
class PrescriptionPhotoTest extends NetworkTestSupport {

    /** The smallest thing that is a PNG by its signature. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    @MockBean PrescriptionReader reader;

    private Appointment visit;

    @BeforeEach
    void completedVisit() {
        Instant start = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(3, ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        visit = appointmentRepository.save(Appointment.builder().slot(slot).patient(meera)
                .status(AppointmentStatus.BOOKED).reason("Heartburn again").scheduledAt(start).build());
        visitService.complete(visit.getId(), doctor.getId());
        when(reader.available()).thenReturn(true);
    }

    @Test
    @DisplayName("only a real image is accepted, and only for the doctor's own completed visit")
    void uploadRules() throws Exception {
        upload(doctorUser, visit.getId(), "not an image".getBytes(), "image/png")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AN_IMAGE"));

        byte[] huge = Arrays.copyOf(PNG, ScanService.MAX_BYTES + 1);
        upload(doctorUser, visit.getId(), huge, "image/png")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IMAGE_SIZE"));

        User otherDoctorUser = persistUser(Role.DOCTOR, "Dr. Suresh Iyer");
        doctorRepository.save(Doctor.builder().user(otherDoctorUser).specialization("Neurology")
                .licenseNumber("KA-PH-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
        upload(otherDoctorUser, visit.getId(), PNG, "image/png").andExpect(status().isForbidden());

        upload(doctorUser, visit.getId(), PNG, "image/png").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("reading gives a draft, matched to the catalogue only when certain, and issues nothing")
    void readingIsOnlyADraft() throws Exception {
        UUID scanId = uploaded();
        when(reader.read(any(), anyString())).thenReturn(new Reading("GERD", List.of(
                new ReadLine("Omeprazole 20 OD AC", omeprazole.getName(), "20mg", "1 capsule",
                        "Once daily before food", 14, 14, 0.92),
                new ReadLine("Pan-D 40 BD", "Pan-D", "40mg", "1 tablet", "Twice daily", 5, 10, 0.6)),
                List.of("the last line")));

        mvc.perform(post("/api/v1/doctors/me/scans/" + scanId + "/read").header("Authorization", bearer(doctorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFTED"))
                .andExpect(jsonPath("$.diagnosis").value("GERD"))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].match.medicineId").value(omeprazole.getId().toString()))
                // Not in the catalogue: left for the doctor, never guessed.
                .andExpect(jsonPath("$.lines[1].match").value(nullValue()))
                .andExpect(jsonPath("$.unreadable[0]").value("the last line"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescriptions WHERE appointment_id = ?",
                Integer.class, visit.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT reading_status FROM prescription_scans WHERE id = ?",
                String.class, scanId)).isEqualTo("DRAFTED");
    }

    @Test
    @DisplayName("without a reader, the doctor is told to type the medicines; the photo stays attached")
    void readerUnavailable() throws Exception {
        when(reader.available()).thenReturn(false);
        UUID scanId = uploaded();

        mvc.perform(post("/api/v1/doctors/me/scans/" + scanId + "/read").header("Authorization", bearer(doctorUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.lines", hasSize(0)));
    }

    @Test
    @DisplayName("the doctor's confirmed prescription carries the photo, which the patient can open and nobody else")
    void confirmedPrescriptionCarriesThePhoto() throws Exception {
        UUID scanId = uploaded();

        issue(scanId, visit).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/patients/me/prescriptions").header("Authorization", bearer(meeraUser)))
                .andExpect(jsonPath("$[?(@.appointmentId == '%s')].hasPhoto".formatted(visit.getId())).value(true));
        UUID rx = jdbc.queryForObject("SELECT id FROM prescriptions WHERE appointment_id = ?", UUID.class, visit.getId());

        mvc.perform(get("/api/v1/patients/me/prescriptions/" + rx + "/scan").header("Authorization", bearer(meeraUser)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(PNG));
        mvc.perform(get("/api/v1/patients/me/prescriptions/" + rx + "/scan").header("Authorization", bearer(otherUser)))
                .andExpect(status().isNotFound());

        // One photo, one prescription.
        assertThat(jdbc.queryForObject("SELECT scan_id FROM prescriptions WHERE id = ?", UUID.class, rx))
                .isEqualTo(scanId);
    }

    @Test
    @DisplayName("a photo from one visit cannot be attached to another")
    void photoBelongsToItsVisit() throws Exception {
        UUID scanId = uploaded();
        Instant start = clock.instant().truncatedTo(ChronoUnit.MINUTES).minus(5, ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment other = appointmentRepository.save(Appointment.builder().slot(slot).patient(meera)
                .status(AppointmentStatus.BOOKED).reason("Other").scheduledAt(start).build());
        visitService.complete(other.getId(), doctor.getId());

        issue(scanId, other).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCAN_FROM_ANOTHER_VISIT"));
    }

    @Test
    @DisplayName("a store the patient asked can see the doctor's original; a store not asked cannot")
    void storesAskedCanSeeTheOriginal() throws Exception {
        UUID scanId = uploaded();
        issue(scanId, visit).andExpect(status().isCreated());
        UUID rx = jdbc.queryForObject("SELECT id FROM prescriptions WHERE appointment_id = ?", UUID.class, visit.getId());
        UUID question = UUID.fromString(JsonPath.read(askRaw(3000, meeraUser, rx).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id"));

        mvc.perform(get("/api/v1/stores/me/requests/" + question).header("Authorization", bearer(nearOwner)))
                .andExpect(jsonPath("$.prescription.hasPhoto").value(true));
        mvc.perform(get("/api/v1/stores/me/requests/" + question + "/scan").header("Authorization", bearer(nearOwner)))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
        mvc.perform(get("/api/v1/stores/me/requests/" + question + "/scan").header("Authorization", bearer(farOwner)))
                .andExpect(status().isNotFound());
    }

    // --- helpers ----------------------------------------------------------------

    private ResultActions upload(User doctorAccount, UUID visitId, byte[] bytes, String type) throws Exception {
        return mvc.perform(multipart("/api/v1/doctors/me/visits/" + visitId + "/scans")
                .file(new MockMultipartFile("photo", "slip.png", type, bytes))
                .header("Authorization", bearer(doctorAccount)));
    }

    private UUID uploaded() throws Exception {
        String body = upload(doctorUser, visit.getId(), PNG, "image/png").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.scanId"));
    }

    private ResultActions issue(UUID scanId, Appointment on) throws Exception {
        return mvc.perform(post("/api/v1/doctors/me/visits/" + on.getId() + "/prescriptions")
                .header("Authorization", bearer(doctorUser)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"diagnosis":"GERD","scanId":"%s","items":[
                          {"medicineId":"%s","dosage":"20mg","frequency":"Once daily before food",
                           "durationDays":14,"quantity":14}]}
                        """.formatted(scanId, omeprazole.getId())));
    }
}
