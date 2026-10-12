package com.medicity.notification.email;

import com.medicity.clinical.PrescribingService;
import com.medicity.clinical.PrescriptionRepository;
import com.medicity.clinical.VisitService;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorHoursService;
import com.medicity.doctor.DoctorRepository;
import com.medicity.jobs.AppointmentReminderJob;
import com.medicity.outbox.OutboxRelay;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.pharmacy.Medicine;
import com.medicity.pharmacy.MedicineRepository;
import com.medicity.scheduling.*;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification emails as an outbox consumer, with a recording fake in place of
 * Resend. The request side (a booking, a cancellation) is real; only the last
 * hop, the provider, is replaced.
 */
@AutoConfigureMockMvc
@Import(EmailNotificationsTest.FakeProvider.class)
@TestPropertySource(properties = {
        "medicity.email.app-url=https://app.test",
        "medicity.email.api-url=https://api.test/"})
@DisplayName("Notification emails")
class EmailNotificationsTest extends AbstractIntegrationTest {

    /** Records what would have been sent, and can be told to refuse. */
    static class FakeEmailSender implements EmailSender {
        volatile boolean available = true;
        volatile int failuresToCome = 0;
        final List<Message> sent = new CopyOnWriteArrayList<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void send(Message message) {
            if (failuresToCome > 0) {
                failuresToCome--;
                throw new SendFailed("Resend refused or could not be reached: 503 Service Unavailable", null);
            }
            sent.add(message);
        }
    }

    @TestConfiguration
    static class FakeProvider {
        @Bean
        @Primary
        FakeEmailSender fakeEmailSender() {
            return new FakeEmailSender();
        }
    }

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeEmailSender email;
    @Autowired OutboxRelay relay;
    @Autowired BookingService bookingService;
    @Autowired VisitService visitService;
    @Autowired PrescribingService prescribingService;
    @Autowired DoctorHoursService hours;
    @Autowired AppointmentReminderJob reminderJob;
    @Autowired UserRepository userRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired PrescriptionRepository prescriptionRepository;
    @Autowired MedicineRepository medicineRepository;
    @Autowired JwtService jwtService;
    @Autowired Clock clock;

    private User patientUser;
    private Patient patient;
    private User doctorUser;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        email.available = true;
        email.failuresToCome = 0;
        email.sent.clear();

        jdbc.update("DELETE FROM prescription_dispensations");
        prescriptionRepository.deleteAllInBatch();
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();
        jdbc.update("DELETE FROM medicines WHERE generic_name LIKE 'em-test-%'");
        jdbc.update("DELETE FROM outbox_events");

        patientUser = persistUser(Role.PATIENT, "Meera Nair");
        patient = patientRepository.save(Patient.builder().user(patientUser)
                .dateOfBirth(LocalDate.of(1994, 8, 12)).gender(Patient.Gender.FEMALE).build());
        doctorUser = persistUser(Role.DOCTOR, "Dr. Anjali Rao");
        doctor = doctorRepository.save(Doctor.builder().user(doctorUser).specialization("Cardiology")
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("900.00")).yearsExperience(10).build());
    }

    // --- the events that email ----------------------------------------------------

    @Test
    @DisplayName("a booking emails the patient with the doctor and the time, and a link to unsubscribe")
    void booking() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), "Check-up");

        deliverEverything();

        assertThat(email.sent).hasSize(1);
        EmailSender.Message m = email.sent.get(0);
        assertThat(m.to()).isEqualTo(patientUser.getEmail());
        assertThat(m.subject()).isEqualTo("Your Medicity appointment is confirmed");
        assertThat(m.text()).contains("Dr. Anjali Rao").contains("IST")
                .contains("https://app.test").contains("https://api.test/api/v1/notifications/email/unsubscribe?u=");
        assertThat(m.text()).doesNotContain("Check-up");          // the reason for the visit stays in the app
        assertThat(m.headers()).containsKeys("List-Unsubscribe", "List-Unsubscribe-Post");
        assertThat(booked.getId()).isNotNull();
    }

    @Test
    @DisplayName("the in-app notification is unaffected by email: both exist")
    void inAppStillThere() {
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);

        deliverEverything();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class,
                patientUser.getId())).isEqualTo(1);
        assertThat(email.sent).hasSize(1);
    }

    @Test
    @DisplayName("a cancellation emails the patient")
    void cancellation() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        deliverEverything();
        email.sent.clear();

        bookingService.cancel(booked.getId(), "Feeling better");
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(email.sent.get(0).subject()).isEqualTo("Your Medicity appointment was cancelled");
        assertThat(email.sent.get(0).text()).doesNotContain("Feeling better");
    }

    @Test
    @DisplayName("moving a visit sends one email, 'moved', not 'moved' and 'confirmed'")
    void reschedule() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        AppointmentSlot later = slot(3, 2);
        deliverEverything();
        email.sent.clear();

        bookingService.reschedule(booked.getId(), later.getId());
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(email.sent.get(0).subject()).isEqualTo("Your Medicity appointment was moved");
    }

    @Test
    @DisplayName("a new prescription says only 'sign in to view': no doctor, diagnosis or medicine")
    void prescriptionIssued() {
        issuePrescription("Type 2 diabetes");

        deliverEverything();

        assertThat(email.sent).hasSize(1);
        EmailSender.Message m = email.sent.get(0);
        assertThat(m.subject()).isEqualTo("You have a new prescription on Medicity");
        String everything = m.subject() + m.text() + m.html();
        assertThat(everything).doesNotContain("diabetes", "Rao", "Paracetamol", "Cardiology");
        assertThat(m.text()).contains("You have a new prescription. Sign in to view it.");
    }

    @Test
    @DisplayName("a corrected prescription emails too, again without details")
    void prescriptionCorrected() {
        var original = issuePrescription("Viral fever");
        deliverEverything();
        email.sent.clear();

        prescribingService.correct(original.getId(), doctor.getId(), draft("Viral fever with cough"));
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(email.sent.get(0).subject()).isEqualTo("A prescription on Medicity was updated");
        assertThat(email.sent.get(0).text()).doesNotContain("cough", "Rao");
    }

    @Test
    @DisplayName("a leave day tells the patient with a visit that day, in the app and by email, once")
    void doctorLeave() throws Exception {
        AppointmentSlot slot = slot(3, 0);
        bookingService.book(slot.getId(), patient.getId(), null);
        deliverEverything();
        email.sent.clear();
        LocalDate day = LocalDate.ofInstant(slot.getStartsAt(), INDIA);

        hours.addLeave(doctorUser.getId(), day, "Surgery on my knee");
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        EmailSender.Message m = email.sent.get(0);
        assertThat(m.to()).isEqualTo(patientUser.getEmail());
        assertThat(m.subject()).isEqualTo("Your Medicity appointment may not go ahead");
        assertThat(m.text() + m.html()).doesNotContain("knee");   // the doctor's note is the doctor's
        mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$.items[0].title").value("Dr. Anjali Rao will be away"));

        // Saving the same day again (say, to edit the note) tells nobody twice.
        email.sent.clear();
        hours.addLeave(doctorUser.getId(), day, "Away");
        deliverEverything();
        assertThat(email.sent).isEmpty();
    }

    @Test
    @DisplayName("a leave day with no visit on it emails nobody")
    void leaveWithoutVisits() {
        hours.addLeave(doctorUser.getId(), LocalDate.now(INDIA).plusDays(10), null);

        deliverEverything();

        assertThat(email.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    // --- the reminder -------------------------------------------------------------

    @Test
    @DisplayName("a visit under 24 hours away, booked days ago, is reminded once however often the job runs")
    void reminder() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        makeItTomorrow(booked.getId(), 20, 30);
        deliverEverything();
        email.sent.clear();

        assertThat(reminderJob.run()).isEqualTo(1);
        assertThat(reminderJob.run()).isZero();
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(email.sent.get(0).subject()).isEqualTo("Reminder: your Medicity appointment is tomorrow");
        assertThat(email.sent.get(0).text()).contains("Dr. Anjali Rao");
    }

    @Test
    @DisplayName("a visit more than 24 hours away is not yet reminded; one booked this morning for tonight never is")
    void reminderBoundaries() {
        Appointment far = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        makeItTomorrow(far.getId(), 30, 60);
        Appointment lastMinute = bookingService.book(slot(3, 2).getId(), persistFamilyMember().getId(), null);
        makeItTomorrow(lastMinute.getId(), 5, 1);
        deliverEverything();
        email.sent.clear();

        assertThat(reminderJob.run()).isZero();
    }

    @Test
    @DisplayName("a cancelled visit is not reminded")
    void cancelledNotReminded() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        makeItTomorrow(booked.getId(), 20, 30);
        bookingService.cancel(booked.getId(), null);

        assertThat(reminderJob.run()).isZero();
    }

    @Test
    @DisplayName("without an email provider the reminder job writes nothing")
    void reminderNoProvider() {
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        makeItTomorrow(booked.getId(), 20, 30);
        email.available = false;

        assertThat(reminderJob.run()).isZero();
    }

    // --- opting out ---------------------------------------------------------------

    @Test
    @DisplayName("the preference defaults to on, can be turned off and on, and is audited")
    void preferenceApi() throws Exception {
        mvc.perform(get("/api/v1/notifications/email-preference").header("Authorization", bearer(patientUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.available").value(true));

        setPreference(patientUser, false).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/notifications/email-preference").header("Authorization", bearer(patientUser)))
                .andExpect(jsonPath("$.enabled").value(false));
        setPreference(patientUser, true).andExpect(jsonPath("$.enabled").value(true));

        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'EMAIL_PREFERENCE_CHANGED' AND entity_id = '"
                + patientUser.getId() + "'")).isEqualTo(2);
    }

    @Test
    @DisplayName("the preference needs a signed-in user, and an empty body is rejected")
    void preferenceNeedsSignIn() throws Exception {
        mvc.perform(get("/api/v1/notifications/email-preference")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/notifications/email-preference").header("Authorization", bearer(patientUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("someone who opted out gets no email, but still gets the in-app notification")
    void optedOut() throws Exception {
        setPreference(patientUser, false).andExpect(status().isOk());

        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        deliverEverything();

        assertThat(email.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND'")).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = '" + patientUser.getId() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("opting out after an email was queued stops it: the preference is checked again at sending")
    void optOutAfterQueued() throws Exception {
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        // Only the fan-out step has run: the email is queued, not sent.
        jdbc.update("""
                INSERT INTO outbox_events (event_type, aggregate_id, payload)
                VALUES ('EMAIL_SEND', ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), """
                {"userId":"%s","template":"BOOKED","doctorName":"Dr. Anjali Rao","forName":"","scheduledAt":"2030-01-07T04:30:00Z"}
                """.formatted(patientUser.getId()));
        setPreference(patientUser, false).andExpect(status().isOk());

        deliverEverything();

        assertThat(email.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND' AND published_at IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the unsubscribe link: opening it changes nothing, the button does, and a forged link does neither")
    void unsubscribeLink() throws Exception {
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        deliverEverything();
        String link = email.sent.get(0).headers().get("List-Unsubscribe");
        Matcher m = Pattern.compile("<https://api\\.test(/[^>]+)>").matcher(link);
        assertThat(m.matches()).isTrue();
        String path = m.group(1);

        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("<form")));
        assertThat(preferenceEnabled(patientUser)).isTrue();            // a mail scanner's GET unsubscribes nobody

        mvc.perform(post(path.replaceAll("t=[^&]+", "t=forged"))).andExpect(status().isBadRequest());
        mvc.perform(post(path.replace(patientUser.getId().toString(), UUID.randomUUID().toString())))
                .andExpect(status().isBadRequest());
        assertThat(preferenceEnabled(patientUser)).isTrue();

        mvc.perform(post(path)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("You are unsubscribed")));
        assertThat(preferenceEnabled(patientUser)).isFalse();
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'EMAIL_UNSUBSCRIBED' AND entity_id = '"
                + patientUser.getId() + "'")).isEqualTo(1);

        email.sent.clear();
        bookingService.book(slot(4, 0).getId(), patient.getId(), null);
        deliverEverything();
        assertThat(email.sent).isEmpty();
    }

    // --- failure, retry, no provider ------------------------------------------------

    @Test
    @DisplayName("a provider failure is logged, audited and retried with backoff; the booking and the in-app notice are untouched")
    void failureIsRetried() {
        email.failuresToCome = 1;
        Appointment booked = bookingService.book(slot(3, 0).getId(), patient.getId(), null);

        deliverEverything();

        assertThat(email.sent).isEmpty();
        var row = jdbc.queryForMap("""
                SELECT attempts, last_error, next_attempt_at, published_at, failed_at
                FROM outbox_events WHERE event_type = 'EMAIL_SEND'""");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("Resend refused");
        assertThat(((java.sql.Timestamp) row.get("next_attempt_at")).toInstant()).isAfter(clock.instant());
        assertThat(row.get("published_at")).isNull();
        // Audited, naming the account and the template but not the address.
        var audit = jdbc.queryForMap("""
                SELECT outcome, detail::text AS detail FROM audit_log
                WHERE action = 'EMAIL_SEND_FAILED' AND entity_id = ?""", patientUser.getId().toString());
        assertThat(audit.get("outcome")).isEqualTo("ERROR");
        assertThat((String) audit.get("detail")).contains("BOOKED").doesNotContain(patientUser.getEmail());
        // The request had succeeded, and its in-app notification is there.
        assertThat(appointmentRepository.findById(booked.getId())).isPresent();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = '" + patientUser.getId() + "'")).isEqualTo(1);

        // Not due yet: nothing happens. Once due, it goes.
        deliverEverything();
        assertThat(email.sent).isEmpty();
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE event_type = 'EMAIL_SEND'");
        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND' AND published_at IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a provider that never recovers: the email is set aside after 10 attempts, every attempt audited")
    void failureGivesUp() {
        email.failuresToCome = Integer.MAX_VALUE;
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);

        for (int attempt = 0; attempt < 10; attempt++) {
            deliverEverything();
            jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second' WHERE event_type = 'EMAIL_SEND'");
        }

        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND' AND failed_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'EMAIL_SEND_FAILED' AND entity_id = '"
                + patientUser.getId() + "'")).isEqualTo(10);
    }

    @Test
    @DisplayName("one person's failing email does not hold back another's")
    void failuresAreIndependent() {
        User second = persistUser(Role.PATIENT, "Arjun Mehta");
        Patient arjun = patientRepository.save(Patient.builder().user(second)
                .dateOfBirth(LocalDate.of(1990, 1, 1)).gender(Patient.Gender.MALE).build());
        email.failuresToCome = 1;
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        bookingService.book(slot(3, 2).getId(), arjun.getId(), null);

        deliverEverything();

        assertThat(email.sent).hasSize(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND' AND published_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("without an email provider nothing is queued or sent, and everything else works as before")
    void noProviderIsANoOp() {
        email.available = false;

        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        deliverEverything();

        assertThat(email.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'EMAIL_SEND'")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = '" + patientUser.getId() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("redelivering the booking event queues no second email")
    void redeliveryQueuesOnce() {
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        deliverEverything();

        jdbc.update("UPDATE outbox_events SET published_at = NULL WHERE event_type = 'APPOINTMENT_BOOKED'");
        deliverEverything();

        assertThat(email.sent).hasSize(1);
    }

    @Test
    @DisplayName("an account disabled since the event was written is not emailed, and the event is not an error")
    void disabledAccount() {
        bookingService.book(slot(3, 0).getId(), patient.getId(), null);
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", patientUser.getId());

        deliverEverything();

        assertThat(email.sent).isEmpty();
        assertThat(count("SELECT count(*) FROM outbox_events WHERE failed_at IS NOT NULL OR attempts > 0")).isZero();
    }

    // --- helpers --------------------------------------------------------------------

    /** Fan-out and delivery are separate outbox events; two passes are enough for both. */
    private void deliverEverything() {
        relay.drain();
        relay.drain();
    }

    private AppointmentSlot slot(int days, int hoursLater) {
        Instant start = clock.instant().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)
                .plus(hoursLater, ChronoUnit.HOURS);
        return slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN).build());
    }

    /** As if booked {@code bookedHoursAgo} ago for a time {@code startsInHours} from now. */
    private void makeItTomorrow(UUID appointmentId, int startsInHours, int bookedHoursAgo) {
        jdbc.update("""
                UPDATE appointments
                SET scheduled_at = now() + make_interval(hours => ?), created_at = now() - make_interval(hours => ?)
                WHERE id = ?""", startsInHours, bookedHoursAgo, appointmentId);
    }

    private Patient persistFamilyMember() {
        return patientRepository.save(Patient.builder().guardianUserId(patientUser.getId()).fullName("Aarav Nair")
                .relationship(Patient.Relationship.CHILD)
                .dateOfBirth(LocalDate.of(2018, 3, 3)).gender(Patient.Gender.MALE).build());
    }

    private PrescribingService.PrescriptionDraft draft(String diagnosis) {
        Medicine medicine = medicineRepository.save(Medicine.builder()
                .name("Paracetamol-" + UUID.randomUUID().toString().substring(0, 6)).genericName("em-test-para")
                .form(Medicine.Form.TABLET).strength("500mg").unitPrice(new BigDecimal("40.00")).build());
        return new PrescribingService.PrescriptionDraft(diagnosis, null,
                List.of(new PrescribingService.PrescriptionDraft.Item(medicine.getId(), "1 tablet", "Three times a day", 3, 9, false)));
    }

    private com.medicity.clinical.Prescription issuePrescription(String diagnosis) {
        Instant start = clock.instant().minus(1, ChronoUnit.HOURS);
        AppointmentSlot slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor).startsAt(start).endsAt(start.plus(30, ChronoUnit.MINUTES)).build());
        Appointment visit = appointmentRepository.save(Appointment.builder()
                .slot(slot).patient(patient).status(AppointmentStatus.BOOKED).reason("Fever").scheduledAt(start).build());
        visitService.complete(visit.getId(), doctor.getId());
        return prescribingService.issue(visit.getId(), doctor.getId(), draft(diagnosis));
    }

    private org.springframework.test.web.servlet.ResultActions setPreference(User user, boolean enabled) throws Exception {
        return mvc.perform(put("/api/v1/notifications/email-preference").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"));
    }

    private boolean preferenceEnabled(User user) {
        return jdbc.query("SELECT enabled FROM user_email_preferences WHERE user_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : true, user.getId());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private User persistUser(Role role, String name) {
        User user = User.builder().passwordHash("{noop}irrelevant").fullName(name).role(role).enabled(true).build();
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@medicity.test");
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issueAccessToken(user);
    }
}
