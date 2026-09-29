package com.medicity.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Records attached to a visit: what is accepted, and who can see them. */
@AutoConfigureMockMvc
@DisplayName("Visit records")
class AttachmentTest extends AbstractIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n% a lab report\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User meeraUser;
    private Patient meera;
    private User raoUser;
    private Doctor rao;
    private User iyerUser;

    @BeforeEach
    void setUp() {
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        meeraUser = persistUser(Role.PATIENT);
        meera = patientRepository.save(Patient.builder().user(meeraUser)
                .dateOfBirth(LocalDate.of(1993, 3, 21)).gender(Patient.Gender.FEMALE).build());
        raoUser = persistUser(Role.DOCTOR);
        rao = doctor(raoUser);
        iyerUser = persistUser(Role.DOCTOR);
        doctor(iyerUser);
    }

    @Test
    @DisplayName("a PDF is attached under a clean name; the doctor opens it, and that is audited")
    void attachAndOpen() throws Exception {
        Appointment visit = visit(2, AppointmentStatus.BOOKED);
        String body = upload(meeraUser, visit, "../../etc/Blood test (May).PDF", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.fileName").value("Blood test (May).pdf"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).path("id").asText();

        mvc.perform(get(url(visit)).header("Authorization", bearer(raoUser)))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get(url(visit) + "/" + id).header("Authorization", bearer(raoUser)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ATTACHMENT_VIEWED'"
                + " AND entity_id = ?", Integer.class, visit.getId().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("a script named .pdf is refused; strangers and other doctors see nothing")
    void onlyRealFilesAndOnlyThem() throws Exception {
        Appointment visit = visit(2, AppointmentStatus.BOOKED);
        upload(meeraUser, visit, "report.pdf", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FILE_TYPE"));
        String id = json.readTree(upload(meeraUser, visit, "x.pdf", PDF).andReturn().getResponse().getContentAsString())
                .path("id").asText();

        User stranger = persistUser(Role.PATIENT);
        patientRepository.save(Patient.builder().user(stranger)
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.MALE).build());
        mvc.perform(get(url(visit)).header("Authorization", bearer(stranger))).andExpect(status().isForbidden());
        mvc.perform(get(url(visit) + "/" + id).header("Authorization", bearer(iyerUser))).andExpect(status().isForbidden());
        upload(stranger, visit, "x.pdf", PDF).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("five files at most, upcoming visits only, and the patient can take one back")
    void limits() throws Exception {
        Appointment visit = visit(2, AppointmentStatus.BOOKED);
        String first = null;
        for (int i = 0; i < 5; i++) {
            String body = upload(meeraUser, visit, "r" + i + ".pdf", PDF).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            if (first == null) first = json.readTree(body).path("id").asText();
        }
        upload(meeraUser, visit, "r6.pdf", PDF).andExpect(jsonPath("$.code").value("TOO_MANY_FILES"));

        mvc.perform(delete(url(visit) + "/" + first).header("Authorization", bearer(meeraUser)))
                .andExpect(status().isNoContent());
        upload(meeraUser, visit, "r6.pdf", PDF).andExpect(status().isCreated());

        upload(meeraUser, visit(-2, AppointmentStatus.COMPLETED), "late.pdf", PDF)
                .andExpect(jsonPath("$.code").value("ATTACH_CLOSED"));
    }

    private ResultActions upload(User user, Appointment visit, String name, byte[] bytes) throws Exception {
        return mvc.perform(multipart(url(visit))
                .file(new MockMultipartFile("file", name, "application/pdf", bytes))
                .param("note", "From last month")
                .header("Authorization", bearer(user)));
    }

    private static String url(Appointment visit) {
        return "/api/v1/appointments/" + visit.getId() + "/attachments";
    }

    private Appointment visit(int days, AppointmentStatus status) {
        Instant start = clock.instant().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder().doctor(rao).startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        return appointmentRepository.save(Appointment.builder().slot(slot).patient(meera).status(status)
                .scheduledAt(start).build());
    }

    private Doctor doctor(User user) {
        return doctorRepository.save(Doctor.builder().user(user).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }

    private User persistUser(Role role) {
        User user = User.builder().passwordHash("{noop}x").fullName("Test " + role.name().toLowerCase())
                .role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }
}
