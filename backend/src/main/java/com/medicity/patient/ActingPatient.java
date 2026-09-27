package com.medicity.patient;

import com.medicity.audit.AuditLog;
import com.medicity.common.NotFoundException;
import com.medicity.common.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

/**
 * Which patient a signed-in account is acting for: themselves, or one of the
 * family members they manage.
 *
 * <p>The portal's routes still say "me" and still take no patient id in the
 * path. A family member is chosen with the {@value #HEADER} header, and this
 * is the one place it is read and checked: the id must be the account's own
 * patient or one of its family members. Any other id answers exactly like an
 * id that does not exist (404), and is audited. Every service that works "as
 * the patient" asks here, so there is no second, weaker check to find.
 *
 * <p>Outside a web request (a job, a test calling a service) there is no
 * header, and the account acts for itself.
 */
@Component
@RequiredArgsConstructor
public class ActingPatient {

    public static final String HEADER = "X-Patient-Id";

    private final PatientRepository patients;
    private final AuditLog auditLog;

    /** The patient this account is acting for in the current request. */
    public Patient resolve(UUID accountUserId) {
        String requested = header();
        if (requested == null || requested.isBlank()) {
            return self(accountUserId);
        }
        UUID patientId;
        try {
            patientId = UUID.fromString(requested.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_PATIENT_ID", HEADER + " must be a patient id");
        }
        return patients.findWithUserById(patientId)
                .filter(p -> accountUserId.equals(p.accountUserId()))
                .orElseGet(() -> {
                    auditLog.recordIndependently("ACCESS_DENIED", "PATIENT", patientId, AuditLog.Outcome.DENIED,
                            Map.of("operation", "act-for"));
                    throw new NotFoundException("Patient", patientId);
                });
    }

    /** The account holder's own patient record. */
    public Patient self(UUID accountUserId) {
        return patients.findWithUserByUserId(accountUserId)
                .orElseThrow(() -> new NotFoundException("Patient profile for user", accountUserId));
    }

    private static String header() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(HEADER);
        }
        return null;
    }
}
