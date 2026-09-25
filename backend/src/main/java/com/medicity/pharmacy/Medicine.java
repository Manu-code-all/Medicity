package com.medicity.pharmacy;

import com.medicity.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "medicines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Medicine extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "generic_name", nullable = false, length = 160)
    private String genericName;

    @Column(name = "manufacturer", length = 160)
    private String manufacturer;

    @Enumerated(EnumType.STRING)
    @Column(name = "form", nullable = false, length = 32)
    private Form form;

    @Column(name = "strength", length = 40)
    private String strength;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Builder.Default
    @Column(name = "prescription_required", nullable = false)
    private boolean prescriptionRequired = true;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    public enum Form { TABLET, CAPSULE, SYRUP, INJECTION, OINTMENT, DROPS, INHALER }
}
