package com.medicity.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize on service and controller methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Without these, a chain that configures no interactive login
            // mechanism silently defaults to Http403ForbiddenEntryPoint, and
            // every unauthenticated request answers 403 instead of 401.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            // No cookie is used for authentication, so there is no CSRF vector to
            // defend: a hostile page cannot make the browser attach a bearer token.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Must come before the public rule below: matchers are checked in
                // order and the first match wins, so without this line every GET
                // under /doctors/me/ would be open to anonymous callers.
                .requestMatchers("/api/v1/doctors/me/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/doctors/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                // Metrics describe the system's internals (routes, error rates,
                // login failures); any signed-in patient could read them before.
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Default-deny. Anything not opened above needs authentication, so
                // adding a new endpoint cannot accidentally expose it.
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 12: roughly 250ms per hash on commodity hardware. Slow enough to
        // make offline cracking expensive, fast enough for interactive login.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * Origins permitted to call the API, from {@code MEDICITY_CORS_ORIGINS}.
     *
     * <p>Configurable because the deployed frontend lives on a different origin
     * from the API, and that origin is not known at build time. Defaults to the
     * local dev servers so a fresh clone needs no configuration.
     */
    @Value("${medicity.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // setAllowedOrigins, not setAllowedOriginPatterns: patterns permit
        // wildcards, and a wildcard here would let any site on the internet read
        // authenticated responses.
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // The web app runs on another origin, so any request header it sends must
        // be listed here or the browser's preflight fails before the request is made.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        // Cross-origin scripts can read only a few response headers unless told otherwise.
        config.setExposedHeaders(List.of("Retry-After", "Idempotent-Replayed"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
