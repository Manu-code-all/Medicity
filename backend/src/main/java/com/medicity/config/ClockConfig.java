package com.medicity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Time is injected, never read from a static.
 *
 * <p>Calls to {@code Instant.now()} scattered through services make time-dependent
 * rules (booking lead time, cancellation windows, token expiry) untestable without
 * sleeping. With a {@link Clock} bean, tests swap in {@code Clock.fixed(...)} and
 * assert boundary behaviour deterministically.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
