package com.medicity.patient;

import com.medicity.audit.AuditLog;
import com.medicity.common.ConflictException;
import com.medicity.security.AppUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The account holder and the family members they manage. A family member has
 * no sign-in; the account holder books, asks the chemists and reads
 * prescriptions for them by choosing them (see {@link ActingPatient}).
 */
@RestController
@RequestMapping("/api/v1/patients/me/family")
@PreAuthorize("hasRole('PATIENT')")
@RequiredArgsConstructor
@Tag(name = "Patient portal")
public class FamilyController {

    /** Enough for a joint family; a cap stops one account becoming a directory. */
    static final int MAX_MEMBERS = 8;

    private final PatientRepository patients;
    private final ActingPatient acting;
    private final AuditLog auditLog;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    @GetMapping
    @Operation(summary = "Me and the family members I manage")
    @Transactional(readOnly = true)
    public List<Member> family(@AuthenticationPrincipal AppUserPrincipal principal) {
        LocalDate today = LocalDate.now(clock);
        List<Member> members = new ArrayList<>();
        members.add(Member.from(acting.self(principal.getId()), today));
        patients.findByGuardianUserIdOrderByCreatedAt(principal.getId())
                .forEach(p -> members.add(Member.from(p, today)));
        return members;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a family member I will manage")
    @Transactional
    public Member add(@AuthenticationPrincipal AppUserPrincipal principal, @Valid @RequestBody MemberRequest request) {
        // Lock the account first, so the count and the insert happen as one step:
        // two adds sent at the same moment at seven members make eight, not nine.
        jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", rs -> {}, principal.getId());
        if (patients.countByGuardianUserId(principal.getId()) >= MAX_MEMBERS) {
            throw new ConflictException("FAMILY_FULL", "An account can manage up to %d family members".formatted(MAX_MEMBERS));
        }
        Patient self = acting.self(principal.getId());
        Patient member = patients.save(Patient.builder()
                .guardianUserId(principal.getId())
                .fullName(request.fullName().trim())
                .relationship(request.relationship())
                .dateOfBirth(request.dateOfBirth())
                .gender(request.gender() == null ? Patient.Gender.UNDISCLOSED : request.gender())
                .bloodGroup(request.bloodGroup())
                // Most families share a home; the address can differ later.
                .addressLine(self.getAddressLine())
                .city(self.getCity())
                .build());
        auditLog.recordChange("FAMILY_MEMBER_ADDED", "PATIENT", member.getId(),
                Map.of("relationship", request.relationship().name()));
        return Member.from(member, LocalDate.now(clock));
    }

    public record MemberRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotNull Patient.Relationship relationship,
            @NotNull @Past LocalDate dateOfBirth,
            Patient.Gender gender,
            @Pattern(regexp = "^(A|B|AB|O)[+-]$", message = "Blood group like O+ or AB-") String bloodGroup
    ) {}

    public record Member(UUID patientId, String fullName, String relationship, boolean self, int age,
                         String gender, String bloodGroup) {
        static Member from(Patient p, LocalDate today) {
            return new Member(p.getId(), p.displayName(),
                    p.getRelationship() == null ? null : p.getRelationship().name(), !p.isFamilyMember(),
                    Period.between(p.getDateOfBirth(), today).getYears(), p.getGender().name(), p.getBloodGroup());
        }
    }
}
