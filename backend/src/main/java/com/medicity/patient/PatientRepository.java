package com.medicity.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByUserId(UUID userId);

    /** For the profile screen, which reads name, email and phone off the user row. */
    @Query("SELECT p FROM Patient p JOIN FETCH p.user u WHERE u.id = :userId")
    Optional<Patient> findWithUserByUserId(@Param("userId") UUID userId);

    /** Any patient, with the user row when there is one (a family member has none). */
    @Query("SELECT p FROM Patient p LEFT JOIN FETCH p.user WHERE p.id = :id")
    Optional<Patient> findWithUserById(@Param("id") UUID id);

    List<Patient> findByGuardianUserIdOrderByCreatedAt(UUID guardianUserId);

    long countByGuardianUserId(UUID guardianUserId);
}
