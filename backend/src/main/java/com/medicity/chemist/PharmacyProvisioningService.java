package com.medicity.chemist;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.common.Constraints;
import com.medicity.common.NotFoundException;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * Creates and suspends pharmacies. Administrator-only, enforced at the
 * controller; kept in a service so the account and the shop are created in one
 * transaction (a pharmacist account without a shop would be a login that can do
 * nothing, and a shop without an account an unreachable one).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PharmacyProvisioningService {

    private static final String UQ_USERS_EMAIL = "uq_users_email";
    private static final String UQ_PHARMACIES_LICENCE = "uq_pharmacies_licence";

    private final UserRepository userRepository;
    private final PharmacyRepository pharmacyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog auditLog;

    public record NewPharmacy(String email, String password, String ownerName,
                              String name, String drugLicenceNumber, String phone,
                              String addressLine, String city, String pincode,
                              double latitude, double longitude,
                              LocalTime opensAt, LocalTime closesAt) {}

    @Transactional
    public Pharmacy create(NewPharmacy in) {
        User owner = User.builder()
                .passwordHash(passwordEncoder.encode(in.password()))
                .fullName(in.ownerName().trim())
                .phone(in.phone())
                .role(Role.PHARMACIST)
                .enabled(true)
                .build();
        owner.setEmail(in.email());

        // Let the unique indexes decide, then translate by constraint name; a
        // check-then-insert would leave a race, and matching on the name keeps an
        // unrelated violation from being reported as "email taken".
        try {
            owner = userRepository.saveAndFlush(owner);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_USERS_EMAIL)) {
                throw new ConflictException("EMAIL_TAKEN", "An account with that email already exists");
            }
            throw e;
        }

        Pharmacy pharmacy = Pharmacy.builder()
                .user(owner)
                .name(in.name().trim())
                .drugLicenceNumber(in.drugLicenceNumber().trim())
                .phone(in.phone())
                .addressLine(in.addressLine().trim())
                .city(in.city().trim())
                .pincode(in.pincode() == null || in.pincode().isBlank() ? null : in.pincode().trim())
                .latitude(in.latitude())
                .longitude(in.longitude())
                .opensAt(in.opensAt())
                .closesAt(in.closesAt())
                .build();
        try {
            pharmacy = pharmacyRepository.saveAndFlush(pharmacy);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_PHARMACIES_LICENCE)) {
                throw new ConflictException("LICENCE_TAKEN",
                        "A pharmacy with that drug licence number already exists");
            }
            throw e;
        }

        auditLog.recordChange("PHARMACY_CREATED", "PHARMACY", pharmacy.getId(),
                Map.of("userId", owner.getId().toString(), "licence", pharmacy.getDrugLicenceNumber()));
        log.info("Provisioned pharmacy {} for pharmacist account {}", pharmacy.getId(), owner.getId());
        return pharmacy;
    }

    /** Hides the shop from search. Idempotent: suspending a suspended shop changes nothing. */
    @Transactional
    public Pharmacy setStatus(UUID pharmacyId, Pharmacy.Status status) {
        Pharmacy pharmacy = pharmacyRepository.findWithUserById(pharmacyId)
                .orElseThrow(() -> new NotFoundException("Pharmacy", pharmacyId));
        if (pharmacy.getStatus() != status) {
            pharmacy.setStatus(status);
            pharmacyRepository.saveAndFlush(pharmacy);
            auditLog.recordChange(status == Pharmacy.Status.SUSPENDED ? "PHARMACY_SUSPENDED" : "PHARMACY_REINSTATED",
                    "PHARMACY", pharmacy.getId(), null);
        }
        return pharmacy;
    }
}
