package com.medicity.clinical;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    /**
     * The patient's current prescriptions, newest first, with everything the
     * portal renders loaded in one round trip.
     *
     * <p>A prescription that has been corrected is excluded: the correction
     * replaces it, and showing both would present a patient with two
     * conflicting sets of instructions. The superseded row is still in the
     * table for the medico-legal record.
     *
     * <p>Returns a list rather than a page because the query fetch-joins a
     * collection; Hibernate cannot apply LIMIT to that in SQL and would page in
     * memory instead. One patient's prescriptions are a small, bounded set.
     */
    @Query("""
            SELECT DISTINCT p FROM Prescription p
            JOIN FETCH p.doctor d
            JOIN FETCH d.user
            LEFT JOIN FETCH p.items i
            LEFT JOIN FETCH i.medicine
            WHERE p.patient.id = :patientId
              AND NOT EXISTS (
                  SELECT 1 FROM Prescription newer WHERE newer.supersedesId = p.id)
            ORDER BY p.issuedAt DESC
            """)
    List<Prescription> findCurrentForPatient(@Param("patientId") UUID patientId);

    @Query("""
            SELECT count(p) FROM Prescription p
            WHERE p.patient.id = :patientId
              AND NOT EXISTS (
                  SELECT 1 FROM Prescription newer WHERE newer.supersedesId = p.id)
            """)
    long countCurrentForPatient(@Param("patientId") UUID patientId);
}
