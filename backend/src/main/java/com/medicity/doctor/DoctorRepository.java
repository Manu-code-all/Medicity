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
     *
     * <p>Every use of an optional parameter is wrapped in {@code cast(... as String)}.
     * When a parameter is null, Hibernate has no value to infer its type from and
     * the PostgreSQL driver sends it untyped, which the server resolves as
     * {@code bytea} — so {@code lower(:specialization)} fails with
     * {@code function lower(bytea) does not exist}. The cast types the parameter
     * regardless of its value. This only fails when the parameter is actually
     * null, so a test that always supplies a filter never sees it.
     */
    @Query("""
            SELECT d FROM Doctor d
            JOIN FETCH d.user u
            WHERE u.enabled = true
              AND (cast(:specialization as String) IS NULL
                   OR lower(d.specialization) = lower(cast(:specialization as String)))
              AND (cast(:nameQuery as String) IS NULL
                   OR lower(u.fullName) LIKE lower(concat('%', cast(:nameQuery as String), '%')))
            """)
    Page<Doctor> search(@Param("specialization") String specialization,
                        @Param("nameQuery") String nameQuery,
                        Pageable pageable);
}
