package com.medicity.security;

import com.medicity.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .map(AppUserPrincipal::new)
                // The same message is used whether the account is missing or the
                // password is wrong, so this endpoint cannot be used to enumerate
                // which email addresses are registered.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Transactional(readOnly = true)
    public UserDetails loadById(UUID id) {
        return userRepository.findById(id)
                .map(AppUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
