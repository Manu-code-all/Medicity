package com.medicity.pharmacy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MedicineRepository extends JpaRepository<Medicine, UUID> {

    @Query("""
            SELECT m FROM Medicine m
            WHERE m.active = true
              AND (:query IS NULL
                   OR lower(m.name) LIKE lower(concat('%', :query, '%'))
                   OR lower(m.genericName) LIKE lower(concat('%', :query, '%')))
            ORDER BY m.name
            """)
    Page<Medicine> search(@Param("query") String query, Pageable pageable);
}
