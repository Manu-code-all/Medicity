package com.medicity.doctor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * An insurer a doctor's clinic accepts (V29). An entity only so the
 * directory's JPQL search can filter on it with {@code EXISTS}; it is read
 * and written in bulk with plain SQL ({@link DoctorOffers}).
 */
@Entity
@Table(name = "doctor_insurance")
@IdClass(DoctorInsurance.Key.class)
@Getter
@NoArgsConstructor
public class DoctorInsurance {

    @Id
    @Column(name = "doctor_id")
    private UUID doctorId;

    @Id
    @Column(name = "insurer")
    private String insurer;

    public record Key(UUID doctorId, String insurer) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
