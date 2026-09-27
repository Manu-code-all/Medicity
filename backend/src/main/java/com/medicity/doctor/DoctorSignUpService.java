package com.medicity.doctor;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import com.medicity.outbox.Outbox;
import com.medicity.security.AuthService;
import com.medicity.security.AuthService.TokenPair;
import com.medicity.user.Role;
import com.medicity.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Doctors registering themselves, and the administrator's check.
 *
 * <p>As on Practo: the doctor gives the medical council they are registered
 * with and their registration number. The account can sign in and set its
 * hours at once, but is not listed and cannot be booked until an
 * administrator has checked the number with the council. The registration
 * number cannot be changed afterwards: it is what was checked.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DoctorSignUpService {

    /**
     * What a doctor can register as: the specialities the clinic offers.
     * Kept closed so the directory's filter and the body guide's
     * recommendations (which name these exactly) stay in step.
     */
    public static final List<String> SPECIALITIES = List.of(
            "Cardiology", "Dentistry", "Dermatology", "ENT", "Gastroenterology", "General Medicine",
            "General Surgery", "Gynaecology", "Nephrology", "Neurology", "Orthopaedics", "Paediatrics",
            "Pulmonology", "Urology");

    private static final String UQ_REGISTRATION = "uq_doctors_license";

    private final AuthService authService;
    private final DoctorRepository doctors;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    @Transactional
    public TokenPair register(Registration r) {
        if (!SPECIALITIES.contains(r.specialization())) {
            throw new ValidationException("UNKNOWN_SPECIALITY", "Choose one of the specialities offered");
        }
        User user = authService.createAccount(r.email(), r.password(), r.fullName(), r.phone(), Role.DOCTOR);
        Doctor doctor = Doctor.builder()
                .user(user)
                .specialization(r.specialization())
                .medicalCouncil(r.medicalCouncil().trim())
                .licenseNumber(r.registrationNumber().trim().toUpperCase())
                .qualification(r.qualification().trim())
                .yearsExperience(r.yearsExperience())
                .consultationFee(r.consultationFee())
                .bio(r.bio() == null || r.bio().isBlank() ? null : r.bio().trim())
                .verifiedAt(null)   // the one path that starts unverified
                .build();
        try {
            doctor = doctors.saveAndFlush(doctor);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_REGISTRATION)) {
                throw new ConflictException("REGISTRATION_TAKEN",
                        "A doctor with this registration number is already on Medicity");
            }
            throw e;
        }
        auditLog.recordChange("DOCTOR_REGISTERED", "DOCTOR", doctor.getId(),
                Map.of("council", doctor.getMedicalCouncil(), "registration", doctor.getLicenseNumber()));
        outbox.publish(Outbox.DOCTOR_REGISTERED, doctor.getId(), Map.of(
                "doctorName", user.getFullName(),
                "specialization", doctor.getSpecialization(),
                "medicalCouncil", doctor.getMedicalCouncil(),
                "registrationNumber", doctor.getLicenseNumber()));
        log.info("Doctor {} registered, awaiting verification", doctor.getId());
        return authService.signInNewAccount(user);
    }

    @Transactional
    public Doctor verify(UUID doctorId) {
        Doctor doctor = doctors.findWithUser(doctorId).orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        if (doctor.isVerified()) {
            throw new ConflictException("ALREADY_VERIFIED", "This doctor is already verified");
        }
        doctor.setVerifiedAt(clock.instant());
        auditLog.recordChange("DOCTOR_VERIFIED", "DOCTOR", doctorId, Map.of("registration", doctor.getLicenseNumber()));
        outbox.publish(Outbox.DOCTOR_VERIFIED, doctorId, Map.of("doctorUserId", doctor.getUser().getId()));
        return doctor;
    }

    @Transactional(readOnly = true)
    public List<Doctor> pending() {
        return doctors.findUnverified();
    }

    public record Registration(String email, String password, String fullName, String phone,
                               String specialization, String medicalCouncil, String registrationNumber,
                               String qualification, int yearsExperience, BigDecimal consultationFee, String bio) {}
}
