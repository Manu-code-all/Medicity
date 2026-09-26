package com.medicity.security;

import com.medicity.audit.AuditLog;
import com.medicity.patient.PatientRepository;
import com.medicity.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failed login must cost the same whether or not the email exists.
 *
 * <p>Asserting on wall-clock time would be flaky, so this checks the property
 * that the timing depends on: for an unknown email, the encoder is handed a
 * hash that is well-formed (so it is actually computed, not rejected on sight)
 * and has the same cost factor as a real stored password (so it takes as long).
 *
 * <p>The regression: the placeholder was a hand-typed literal of the wrong
 * length. Spring's encoder checks the whole string against this pattern and
 * returns {@code false} immediately on a mismatch, so unknown emails answered
 * ~220 ms faster in production and account existence could be probed by timing.
 */
@DisplayName("Login timing")
class LoginTimingTest {

    /** The exact full-string check {@code BCryptPasswordEncoder} applies before hashing. */
    private static final Pattern WELL_FORMED_BCRYPT =
            Pattern.compile("\\A\\$2(a|y|b)?\\$(\\d\\d)\\$[./0-9A-Za-z]{53}");

    @Test
    @DisplayName("an unknown email is checked against a real hash of the same cost")
    void unknownEmailCostsAFullHash() {
        // Cost 4 keeps the test fast; the assertion is relative to whatever
        // cost the encoder uses, which is the point.
        RecordingEncoder encoder = new RecordingEncoder(new BCryptPasswordEncoder(4));

        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());

        AuthService auth = new AuthService(users, mock(PatientRepository.class), encoder, mock(JwtService.class),
                mock(AuditLog.class), mock(RefreshTokenStore.class), mock(LoginThrottle.class),
                Duration.ofDays(7), Clock.systemUTC());

        assertThatThrownBy(() -> auth.login("nobody@medicity.test", "some-password-123"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(encoder.checkedHashes).hasSize(1);
        String checked = encoder.checkedHashes.get(0);
        String realHash = encoder.encode("any-real-password");

        assertThat(WELL_FORMED_BCRYPT.matcher(checked).matches())
                .as("placeholder must be well-formed, or it is rejected without hashing: %s", checked)
                .isTrue();
        assertThat(costOf(checked))
                .as("placeholder must cost as much as a real password")
                .isEqualTo(costOf(realHash));
    }

    private static String costOf(String bcryptHash) {
        return bcryptHash.split("\\$")[2];
    }

    /** Delegates to a real encoder and remembers every hash it was asked to check. */
    private static final class RecordingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;
        private final List<String> checkedHashes = new ArrayList<>();

        RecordingEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            checkedHashes.add(encodedPassword);
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
