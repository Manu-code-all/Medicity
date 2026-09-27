package com.medicity.patient;

import com.medicity.common.BaseEntity;
import com.medicity.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Someone who is treated. Either an account holder, who signs in ({@link #user}
 * set), or a family member managed by one ({@link #guardianUserId} set, with
 * their own name here). The database allows exactly one of the two (V18).
 *
 * <p>Code that needs "who is this" or "whom do we tell" uses
 * {@link #displayName()} and {@link #accountUserId()}, never
 * {@code getUser()} directly: a family member has no user.
 */
@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient extends BaseEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** The patient's own sign-in; null for a family member. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    /** The account holder who manages this family member; null for an account holder. */
    @Column(name = "guardian_user_id", updatable = false)
    private UUID guardianUserId;

    /** A family member's name. An account holder's name is on their user row. */
    @Column(name = "full_name", length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship", length = 16)
    private Relationship relationship;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, length = 16)
    private Gender gender;

    @Column(name = "blood_group", length = 3)
    private String bloodGroup;

    @Column(name = "address_line", length = 200)
    private String addressLine;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "emergency_contact", length = 20)
    private String emergencyContact;

    public boolean isFamilyMember() {
        return guardianUserId != null;
    }

    public String displayName() {
        return user != null ? user.getFullName() : fullName;
    }

    /**
     * For a message to the account holder about someone they manage: the family
     * member's first name, or empty when the patient is the account holder.
     */
    public String forName() {
        if (!isFamilyMember()) {
            return "";
        }
        String name = displayName().trim();
        int space = name.indexOf(' ');
        return space < 0 ? name : name.substring(0, space);
    }

    /** The account that signs in for this patient, and receives their notifications. */
    public UUID accountUserId() {
        return user != null ? user.getId() : guardianUserId;
    }

    public enum Gender { MALE, FEMALE, OTHER, UNDISCLOSED }

    /** The family member's relationship to the account holder. */
    public enum Relationship { PARENT, CHILD, SPOUSE, SIBLING, OTHER }
}
