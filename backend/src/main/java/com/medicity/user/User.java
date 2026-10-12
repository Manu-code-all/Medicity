package com.medicity.user;

import com.medicity.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

/**
 * The authentication principal. Role-specific data lives in {@code Doctor} /
 * {@code Patient} so this table stays narrow — it is read on every request.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Always stored lowercase. A database CHECK enforces this so a bug in a
     * future code path cannot create a case-variant duplicate account.
     */
    @Email
    @NotBlank
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @NotBlank
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Set on accounts a clinic created with a temporary password. While true
     * the API accepts nothing but changing the password (V46).
     */
    @Builder.Default
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase();
    }
}
