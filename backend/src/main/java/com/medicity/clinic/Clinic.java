package com.medicity.clinic;

import com.medicity.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A clinic or hospital that lists its doctors on Medicity as one organisation
 * (V44). Its doctors point at it with {@code doctors.clinic_id}; its staff are
 * the rows of {@code clinic_staff}.
 */
@Entity
@Table(name = "clinics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Clinic extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "address", length = 200)
    private String address;

    @Column(name = "city", length = 80)
    private String city;

    /** Both coordinates or neither (a CHECK in V44). */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    /** An IANA zone id. Decides where a "day" starts and ends in the clinic's schedule and report. */
    @Builder.Default
    @Column(name = "timezone", nullable = false, length = 40)
    private String timezone = "Asia/Kolkata";

    /** An inactive clinic is not listed and its staff cannot work. Set by the platform administrator. */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
