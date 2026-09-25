package com.medicity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the bearer token and populates the security context.
 *
 * <p>The user is re-loaded from the database on each request rather than trusted
 * from the token claims. That costs a lookup, but it means disabling an account
 * or changing a role takes effect immediately instead of whenever the token
 * happens to expire — which, for a system holding clinical records, is the right
 * trade. The lookup is a primary-key hit and a natural place to add a cache.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        jwtService.verifyAccessToken(header.substring(PREFIX.length()))
                .ifPresent(userId -> {
                    try {
                        UserDetails user = userDetailsService.loadById(userId);
                        if (!user.isEnabled()) {
                            return;
                        }
                        var auth = new UsernamePasswordAuthenticationToken(
                                user, null, user.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (UsernameNotFoundException e) {
                        // Valid signature, but the account no longer exists. Leave
                        // the context anonymous and let authorization reject it.
                    }
                });

        chain.doFilter(request, response);
    }
}
