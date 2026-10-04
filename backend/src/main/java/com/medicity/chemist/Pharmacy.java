package com.medicity.chemist;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A chemist's shop that patients can be sent to.
 *
 * <p>Not to be confused with {@code com.medicity.pharmacy}, the hospital's own
 * stock desk (catalogue, stock ledger, dispensing). That package manages one
 * inventory; this one models the independent shops around the patient.
 */
@Entity
@Table(name = "pharmacies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pharmacy extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "drug_licence_number", nullable = false, length = 40)
    private String drugLicenceNumber;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "pincode", length = 10)
    private String pincode;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Builder.Default
    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt = LocalTime.of(9, 0);

    @Builder.Default
    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt = LocalTime.of(21, 0);

    @Builder.Default
    @Column(name = "timezone", nullable = false, length = 40)
    private String timezone = "Asia/Kolkata";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    public enum Status { ACTIVE, SUSPENDED }

    /** Whether the shop is open at {@code instant}, by its own local clock. */
    public boolean isOpenAt(Instant instant) {
        return isOpen(opensAt, closesAt, timezone, instant);
    }

    /**
     * Opens inclusive, closes exclusive. A shop whose closing time is earlier
     * than its opening time stays open past midnight (22:00 to 02:00).
     *
     * <p>Static so the nearby search, which reads plain columns rather than
     * entities, applies the same rule.
     */
    public static boolean isOpen(LocalTime opensAt, LocalTime closesAt, String timezone, Instant instant) {
        LocalTime local = instant.atZone(ZoneId.of(timezone)).toLocalTime();
        if (opensAt.isBefore(closesAt)) {
            return !local.isBefore(opensAt) && local.isBefore(closesAt);
        }
        return !local.isBefore(opensAt) || local.isBefore(closesAt);
    }
}
