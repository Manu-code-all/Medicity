package com.medicity.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Timestamps and the optimistic-locking version shared by every mutable entity.
 *
 * <p>{@code @Version} matters for rows that are read, decided upon, and written
 * back in separate statements (profile edits, slot status changes). It does NOT
 * protect the booking path — that relies on database constraints instead,
 * because optimistic locking cannot prevent two INSERTs of different rows that
 * happen to claim the same resource.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
