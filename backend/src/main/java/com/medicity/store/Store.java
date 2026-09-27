package com.medicity.store;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A neighbourhood chemist's store (V14). Owned by exactly one CHEMIST account.
 *
 * <p>Receives patients' questions only once {@link #verifiedAt} is set by an
 * administrator who has checked the drug licence.
 */
@Entity
@Table(name = "stores")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Store extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false, updatable = false)
    private User owner;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "licence_number", nullable = false, length = 40)
    private String licenceNumber;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    @Builder.Default
    @Column(name = "open_24h", nullable = false)
    private boolean open24h = false;

    @Builder.Default
    @Column(name = "time_zone", nullable = false, length = 40)
    private String timeZone = "Asia/Kolkata";

    @Builder.Default
    @Column(name = "hold_hours", nullable = false)
    private short holdHours = 3;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isOpenAt(Instant instant) {
        return StoreHours.isOpen(open24h, opensAt, closesAt, ZonedDateTime.ofInstant(instant, ZoneId.of(timeZone)));
    }
}
