package com.medicity.scheduling;

import com.medicity.common.ConflictException;
import com.medicity.doctor.Doctor;
import com.medicity.doctor.DoctorRepository;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.support.AbstractIntegrationTest;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the central safety property of the platform:
 *
 * <blockquote><b>No matter how many patients race for the same slot, exactly one
 * booking succeeds.</b></blockquote>
 *
 * <p>This is the test that justifies the design in {@link BookingService}. Every
 * naive implementation of booking passes a single-threaded test; only this one
 * distinguishes a correct implementation from a broken one.
 *
 * <p><b>Why a latch rather than just launching threads:</b> submitting N tasks to a
 * pool staggers their starts by microseconds — often enough for each INSERT to
 * commit before the next begins, so the race never actually occurs and the test
 * passes vacuously. The {@link CountDownLatch} parks every thread until all of
 * them are alive and ready, then releases them together, maximising real overlap
 * inside the critical section.
 */
@DisplayName("Concurrent slot booking")
class SlotBookingConcurrencyTest extends AbstractIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired SlotRepository slotRepository;
    @Autowired AppointmentRepository appointmentRepository;
    @Autowired DoctorRepository doctorRepository;
    @Autowired PatientRepository patientRepository;
    @Autowired UserRepository userRepository;
    @Autowired Clock clock;

    private AppointmentSlot slot;
    private List<UUID> patientIds;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();
        slotRepository.deleteAll();
        patientRepository.deleteAll();
        doctorRepository.deleteAll();
        userRepository.deleteAll();

        Doctor doctor = persistDoctor("dr.rao@medicity.test", "Dr. Anjali Rao", "Cardiology");

        Instant start = clock.instant().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        slot = slotRepository.save(AppointmentSlot.builder()
                .doctor(doctor)
                .startsAt(start)
                .endsAt(start.plus(30, ChronoUnit.MINUTES))
                .status(SlotStatus.OPEN)
                .build());

        patientIds = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            patientIds.add(persistPatient("patient" + i + "@medicity.test", "Patient " + i).getId());
        }
    }

    @ParameterizedTest(name = "{0} patients race for one slot -> exactly 1 wins")
    @ValueSource(ints = {2, 8, 32, 64})
    @DisplayName("exactly one booking survives, regardless of contention level")
    void onlyOneBookingSurvives(int contenders) throws Exception {
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejectedAsConflict = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                UUID patientId = patientIds.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        // Every thread blocks here until the last one arrives, so
                        // the INSERTs genuinely overlap instead of queueing.
                        go.await();
                        bookingService.book(slot.getId(), patientId, "Chest pain");
                        succeeded.incrementAndGet();
                    } catch (ConflictException expected) {
                        rejectedAsConflict.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all contender threads should reach the starting line")
                    .isTrue();
            go.countDown();

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Any exception other than ConflictException means the race produced a
        // failure mode we did not design for — a leaked constraint violation, a
        // 500, a deadlock. Surface it rather than letting the counts hide it.
        assertThat(unexpected)
                .as("contended booking should only ever fail with ConflictException")
                .isEmpty();

        assertThat(succeeded.get())
                .as("exactly one patient should win the slot")
                .isEqualTo(1);

        assertThat(rejectedAsConflict.get())
                .as("every loser should get a clean domain conflict")
                .isEqualTo(contenders - 1);

        // The counters above describe what the application *thought* happened.
        // This asserts what the database actually contains — the only claim that
        // matters to the patient standing at reception.
        assertThat(appointmentRepository.findAll())
                .as("the database must hold exactly one live appointment for the slot")
                .hasSize(1)
                .allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AppointmentStatus.BOOKED));
    }

    @Test
    @DisplayName("cancelling releases the slot for a new patient")
    void cancellationFreesTheSlot() {
        Appointment first = bookingService.book(slot.getId(), patientIds.get(0), "Follow-up");

        // While it is live, nobody else can take the slot.
        assertThatThrownBy(() -> bookingService.book(slot.getId(), patientIds.get(1), "Follow-up"))
                .isInstanceOf(ConflictException.class);

        bookingService.cancel(first.getId(), "Patient rescheduled");

        // The partial unique index ignores CANCELLED rows, so the slot is bookable
        // again without any separate slot-state bookkeeping.
        Appointment second = bookingService.book(slot.getId(), patientIds.get(1), "Follow-up");

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(appointmentRepository.findActiveBySlot(slot.getId()))
                .get()
                .satisfies(a -> assertThat(a.getId()).isEqualTo(second.getId()));
    }

    @Test
    @DisplayName("a patient cannot hold two appointments at the same instant")
    void patientCannotDoubleBookThemselves() {
        Doctor other = persistDoctor("dr.iyer@medicity.test", "Dr. Suresh Iyer", "Neurology");

        // A different doctor, a different slot — but the same wall-clock time.
        AppointmentSlot clashing = slotRepository.save(AppointmentSlot.builder()
                .doctor(other)
                .startsAt(slot.getStartsAt())
                .endsAt(slot.getEndsAt())
                .status(SlotStatus.OPEN)
                .build());

        UUID patient = patientIds.get(0);
        bookingService.book(slot.getId(), patient, "Cardiology review");

        assertThatThrownBy(() -> bookingService.book(clashing.getId(), patient, "Neurology review"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have an appointment");
    }

    // --- fixtures -------------------------------------------------------

    private Doctor persistDoctor(String email, String name, String specialization) {
        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(Role.DOCTOR)
                .enabled(true)
                .build());

        return doctorRepository.save(Doctor.builder()
                .user(user)
                .specialization(specialization)
                .licenseNumber("LIC-" + UUID.randomUUID().toString().substring(0, 8))
                .consultationFee(new BigDecimal("800.00"))
                .yearsExperience(12)
                .build());
    }

    private Patient persistPatient(String email, String name) {
        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash("{noop}irrelevant")
                .fullName(name)
                .role(Role.PATIENT)
                .enabled(true)
                .build());

        return patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(LocalDate.of(1995, 6, 15))
                .gender(Patient.Gender.UNDISCLOSED)
                .build());
    }
}
