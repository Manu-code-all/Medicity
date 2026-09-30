package com.medicity.doctor;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "doctors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Doctor extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * LAZY because doctor rows are listed in bulk on the search screen and the
     * user row is only needed when rendering a single profile.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "specialization", nullable = false, length = 80)
    private String specialization;

    @Column(name = "license_number", nullable = false, length = 40)
    private String licenseNumber;

    @Column(name = "consultation_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal consultationFee;

    @Builder.Default
    @Column(name = "years_experience", nullable = false)
    private int yearsExperience = 0;

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    /** Set for doctors who registered themselves; the clinic's own doctors have none. */
    @Column(name = "medical_council", length = 80)
    private String medicalCouncil;

    @Column(name = "qualification", length = 120)
    private String qualification;

    /** Where patients are seen. Null until the doctor sets it; coordinates are both set or both null. */
    @Column(name = "clinic_name", length = 120)
    private String clinicName;

    @Column(name = "clinic_address", length = 200)
    private String clinicAddress;

    @Column(name = "clinic_latitude")
    private Double clinicLatitude;

    @Column(name = "clinic_longitude")
    private Double clinicLongitude;

    /**
     * When the registration number was checked. Null only for a doctor who
     * signed up and is waiting: not listed, not bookable. A doctor created
     * any other way is one the clinic vouches for, so the default is now.
     */
    @Builder.Default
    @Column(name = "verified_at")
    private Instant verifiedAt = Instant.now();

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
