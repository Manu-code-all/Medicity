package com.medicity.pharmacy;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MedicineRepository extends JpaRepository<Medicine, UUID> {

    /**
     * The casts are load-bearing; see {@code DoctorRepository.search} for why a
     * bare {@code :query IS NULL} fails on PostgreSQL when the argument is null.
     */
    @Query("""
            SELECT m FROM Medicine m
            WHERE m.active = true
              AND (cast(:query as String) IS NULL
                   OR lower(m.name) LIKE lower(concat('%', cast(:query as String), '%'))
                   OR lower(m.genericName) LIKE lower(concat('%', cast(:query as String), '%')))
            ORDER BY m.name
            """)
    Page<Medicine> search(@Param("query") String query, Pageable pageable);
}
