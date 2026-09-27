package com.medicity.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreRepository extends JpaRepository<Store, UUID> {

    /** With the owner, which every response shows. */
    @Query("SELECT s FROM Store s JOIN FETCH s.owner o WHERE o.id = :ownerUserId")
    Optional<Store> findByOwnerId(@Param("ownerUserId") UUID ownerUserId);

    /** Stores waiting for an administrator, oldest first. Served by idx_stores_unverified. */
    @Query("SELECT s FROM Store s JOIN FETCH s.owner WHERE s.verifiedAt IS NULL ORDER BY s.createdAt")
    List<Store> findUnverified();

    @Query("SELECT s FROM Store s JOIN FETCH s.owner WHERE s.id = :id")
    Optional<Store> findWithOwner(@Param("id") UUID id);
}
