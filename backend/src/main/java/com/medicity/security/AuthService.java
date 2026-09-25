package com.medicity.security;

import com.medicity.common.ConflictException;
import com.medicity.common.ValidationException;
import com.medicity.patient.Patient;
import com.medicity.patient.PatientRepository;
import com.medicity.user.Role;
import com.medicity.user.User;
import com.medicity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Self-service registration, which creates a PATIENT only.
     *
     * <p>Doctor and admin accounts are provisioned by an administrator. If the
     * requested role came from the request body, anyone could mint themselves an
     * admin account — a privilege-escalation hole that appears in a surprising
     * number of tutorial codebases.
     */
    @Transactional
    public TokenPair register(String email, String rawPassword, String fullName,
                              String phone, LocalDate dateOfBirth, Patient.Gender gender) {

        User user = User.builder()
                .passwordHash(passwordEncoder.encode(rawPassword))
                .fullName(fullName.trim())
                .phone(phone)
                .role(Role.PATIENT)
                .enabled(true)
                .build();
        user.setEmail(email);   // setter normalises to lowercase

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Checking existsByEmail() first would leave a race between the check
            // and the insert. Let the unique index decide, then translate.
            throw new ConflictException("EMAIL_TAKEN", "An account with that email already exists");
        }

        patientRepository.save(Patient.builder()
                .user(user)
                .dateOfBirth(dateOfBirth)
                .gender(gender == null ? Patient.Gender.UNDISCLOSED : gender)
                .build());

        log.info("Registered patient account {}", user.getId());
        return issue(user);
    }

    @Transactional(readOnly = true)
    public TokenPair login(String email, String rawPassword) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElse(null);

        // Hash even when the user does not exist, so response time does not reveal
        // whether an address is registered. Comparing against a throwaway hash
        // keeps the timing profile of both branches roughly equal.
        String storedHash = user != null
                ? user.getPasswordHash()
                : "$2a$12$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidinv";

        boolean matches = passwordEncoder.matches(rawPassword, storedHash);

        if (user == null || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw new ValidationException("ACCOUNT_DISABLED", "This account has been disabled");
        }
        return issue(user);
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>{@link JwtService} rejects an access token presented here, so the two
     * token types cannot be substituted for one another.
     */
    @Transactional(readOnly = true)
    public TokenPair refresh(String refreshToken) {
        return jwtService.verifyRefreshToken(refreshToken)
                .flatMap(userRepository::findById)
                .filter(User::isEnabled)
                .map(this::issue)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));
    }

    private TokenPair issue(User user) {
        return new TokenPair(
                jwtService.issueAccessToken(user),
                jwtService.issueRefreshToken(user),
                user.getId().toString(),
                user.getRole().name(),
                user.getFullName());
    }

    public record TokenPair(String accessToken,
                            String refreshToken,
                            String userId,
                            String role,
                            String fullName) {}
}
