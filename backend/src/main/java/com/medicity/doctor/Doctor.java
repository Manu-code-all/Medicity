package com.medicity.doctor;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
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
}
