package com.medicity.patient;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 16)
    private Gender gender;

    @Column(name = "blood_group", length = 3)
    private String bloodGroup;

    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "emergency_contact", length = 20)
    private String emergencyContact;

    public enum Gender { MALE, FEMALE, OTHER, UNDISCLOSED }
}
