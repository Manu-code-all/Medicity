package com.medicity.doctor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DoctorRepository extends JpaRepository<Doctor, UUID> {

    Optional<Doctor> findByUserId(UUID userId);

    /**
     * Doctor search. The join fetch avoids the N+1 that would otherwise fire
     * once per row when the response mapper reads {@code doctor.user.fullName}.
     */
    @Query("""
            SELECT d FROM Doctor d
            JOIN FETCH d.user u
            WHERE u.enabled = true
              AND (:specialization IS NULL OR lower(d.specialization) = lower(:specialization))
              AND (:nameQuery IS NULL OR lower(u.fullName) LIKE lower(concat('%', :nameQuery, '%')))
            """)
    Page<Doctor> search(@Param("specialization") String specialization,
                        @Param("nameQuery") String nameQuery,
                        Pageable pageable);
}
