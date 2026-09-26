package com.medicity.patient;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Optional<Patient> findByUserId(UUID userId);

    /** For the profile screen, which reads name, email and phone off the user row. */
    @Query("SELECT p FROM Patient p JOIN FETCH p.user u WHERE u.id = :userId")
    Optional<Patient> findWithUserByUserId(@Param("userId") UUID userId);
}
