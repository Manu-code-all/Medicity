package com.medicity.doctor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
              AND d.verifiedAt IS NOT NULL
              AND (cast(:specialization as String) IS NULL
                   OR lower(d.specialization) = lower(cast(:specialization as String)))
              AND (cast(:nameQuery as String) IS NULL
                   OR lower(u.fullName) LIKE lower(concat('%', cast(:nameQuery as String), '%'))
                   OR lower(d.specialization) LIKE lower(concat('%', cast(:nameQuery as String), '%')))
              AND (cast(:insurance as String) IS NULL
                   OR EXISTS (SELECT 1 FROM DoctorInsurance i
                              WHERE i.doctorId = d.id AND i.insurer = cast(:insurance as String)))
            """)
    Page<Doctor> search(@Param("specialization") String specialization,
                        @Param("nameQuery") String nameQuery,
                        @Param("insurance") String insurance, Pageable pageable);

    /** Without the insurance filter. */
    default Page<Doctor> search(String specialization, String nameQuery, Pageable pageable) {
        return search(specialization, nameQuery, null, pageable);
    }

    /** Every specialisation with at least one doctor who can be booked, and how many. */
    @Query("""
            SELECT d.specialization AS name, count(d) AS doctors
            FROM Doctor d JOIN d.user u
            WHERE u.enabled = true
              AND d.verifiedAt IS NOT NULL
              AND (cast(:contains as String) IS NULL
                   OR lower(d.specialization) LIKE lower(concat('%', cast(:contains as String), '%')))
            GROUP BY d.specialization
            ORDER BY d.specialization
            """)
    List<SpecialtyCount> specialties(@Param("contains") String contains);

    /** Doctors who signed up and wait for their registration to be checked, oldest first. */
    @Query("SELECT d FROM Doctor d JOIN FETCH d.user WHERE d.verifiedAt IS NULL ORDER BY d.createdAt")
    List<Doctor> findUnverified();

    /** With the account loaded, for responses that show the name and email. */
    @Query("SELECT d FROM Doctor d JOIN FETCH d.user u WHERE u.id = :userId")
    Optional<Doctor> findWithUserByUserId(@Param("userId") UUID userId);

    @Query("SELECT d FROM Doctor d JOIN FETCH d.user WHERE d.id = :id")
    Optional<Doctor> findWithUser(@Param("id") UUID id);

    interface SpecialtyCount {
        String getName();

        long getDoctors();
    }
}
