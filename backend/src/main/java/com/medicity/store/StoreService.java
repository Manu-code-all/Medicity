package com.medicity.store;

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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/** Registering, editing and verifying chemists' stores. */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreService {

    private static final String UQ_LICENCE = "uq_stores_licence";

    private final AuthService authService;
    private final StoreRepository storeRepository;
    private final AuditLog auditLog;
    private final Outbox outbox;
    private final Clock clock;

    /**
     * A chemist signs up with their store in one step. The store starts
     * unverified: the account can sign in and edit the profile, but receives
     * no patient questions until an administrator has checked the licence.
     */
    @Transactional
    public TokenPair register(String email, String rawPassword, String fullName, StoreDetails details) {
        User owner = authService.createAccount(email, rawPassword, fullName, details.phone(), Role.CHEMIST, false);
        Store store = Store.builder().owner(owner).build();
        details.applyTo(store);
        store.setLicenceNumber(details.licenceNumber().trim().toUpperCase());
        store = saveTranslatingLicence(store);

        auditLog.recordChange("STORE_REGISTERED", "STORE", store.getId(),
                Map.of("licence", store.getLicenceNumber(), "city", store.getCity()));
        outbox.publish(Outbox.STORE_REGISTERED, store.getId(), Map.of(
                "storeName", store.getName(), "city", store.getCity()));
        log.info("Store {} registered by {}, awaiting verification", store.getId(), owner.getId());
        return authService.signInNewAccount(owner);
    }

    /**
     * The owner edits their profile. The licence number is not editable: it
     * is what was verified, and changing it would carry that verification over
     * to a licence nobody checked.
     */
    @Transactional
    public Store update(UUID ownerUserId, StoreDetails details) {
        Store store = requireOwn(ownerUserId);
        details.applyTo(store);
        auditLog.recordChange("STORE_UPDATED", "STORE", store.getId(), null);
        return store;
    }

    @Transactional
    public Store verify(UUID storeId) {
        Store store = storeRepository.findWithOwner(storeId)
                .orElseThrow(() -> new NotFoundException("Store", storeId));
        if (store.isVerified()) {
            throw new ConflictException("ALREADY_VERIFIED", "This store is already verified");
        }
        store.setVerifiedAt(clock.instant());
        auditLog.recordChange("STORE_VERIFIED", "STORE", storeId, Map.of("licence", store.getLicenceNumber()));
        outbox.publish(Outbox.STORE_VERIFIED, storeId, Map.of(
                "ownerUserId", store.getOwner().getId(), "storeName", store.getName()));
        return store;
    }

    @Transactional(readOnly = true)
    public Store requireOwn(UUID ownerUserId) {
        return storeRepository.findByOwnerId(ownerUserId)
                .orElseThrow(() -> new NotFoundException("Store for user", ownerUserId));
    }

    private Store saveTranslatingLicence(Store store) {
        try {
            return storeRepository.saveAndFlush(store);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolationOf(e, UQ_LICENCE)) {
                throw new ConflictException("LICENCE_TAKEN", "A store with this drug licence is already registered");
            }
            throw e;
        }
    }

    /** The editable part of a store profile. Validated at the HTTP boundary. */
    public record StoreDetails(String name, String licenceNumber, String phone, String addressLine, String city,
                               double latitude, double longitude, LocalTime opensAt, LocalTime closesAt,
                               boolean open24h, int holdHours) {

        void applyTo(Store store) {
            if (!open24h && (opensAt == null || closesAt == null || opensAt.equals(closesAt))) {
                throw new ValidationException("INVALID_HOURS",
                        "Give an opening and a closing time, or mark the store open 24 hours");
            }
            store.setName(name.trim());
            store.setPhone(phone.trim());
            store.setAddressLine(addressLine.trim());
            store.setCity(city.trim());
            // Six decimals is about 11 cm, the column's precision.
            store.setLatitude(BigDecimal.valueOf(latitude).setScale(6, RoundingMode.HALF_UP));
            store.setLongitude(BigDecimal.valueOf(longitude).setScale(6, RoundingMode.HALF_UP));
            store.setOpen24h(open24h);
            // A 24-hour store still needs two different times for the CHECK;
            // they are ignored while open24h is set.
            store.setOpensAt(open24h ? LocalTime.MIDNIGHT : opensAt);
            store.setClosesAt(open24h ? LocalTime.of(23, 59) : closesAt);
            store.setHoldHours((short) holdHours);
        }
    }
}
