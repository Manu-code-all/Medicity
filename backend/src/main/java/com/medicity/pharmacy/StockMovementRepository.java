package com.medicity.pharmacy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByMedicineIdOrderByOccurredAtDesc(UUID medicineId);
}
