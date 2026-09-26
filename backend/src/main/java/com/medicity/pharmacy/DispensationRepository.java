package com.medicity.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DispensationRepository extends JpaRepository<Dispensation, UUID> {

    /** One query for a whole list of prescriptions, not one per prescription. */
    List<Dispensation> findByPrescriptionIdIn(Collection<UUID> prescriptionIds);
}
