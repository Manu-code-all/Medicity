package com.medicity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
    private final AccessTokenDenylist denylist;
    private final ObjectMapper objectMapper;

    /** Where {@link AccountController} lets an account with a temporary password change it. */
    static final String CHANGE_PASSWORD_PATH = "/api/v1/account/change-password";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        UserDetails user = jwtService.verifyAccessToken(header.substring(PREFIX.length()))
                .map(token -> {
                    try {
                        // Signed out: valid signature and not expired, but revoked.
                        if (token.tokenId() != null && denylist.isRevoked(token.tokenId())) {
                            return null;
                        }
                        UserDetails loaded = userDetailsService.loadById(token.userId());
                        return loaded.isEnabled() ? loaded : null;
                    } catch (UsernameNotFoundException e) {
                        // Valid signature, but the account no longer exists. Leave
                        // the context anonymous and let authorization reject it.
                        return null;
                    }
                })
                .orElse(null);

        if (user != null) {
            var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);

            // An account a clinic created with a temporary password may do one
            // thing: change it. Signing out and refreshing stay open under /auth.
            if (user instanceof AppUserPrincipal principal && principal.isMustChangePassword()
                    && !allowedWhileTemporary(request)) {
                refuseUntilPasswordChanged(request, response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private static boolean allowedWhileTemporary(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equals(request.getMethod())
                || path.equals(CHANGE_PASSWORD_PATH)
                || path.startsWith("/api/v1/auth/");
    }

    private void refuseUntilPasswordChanged(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Choose a new password before doing anything else");
        problem.setTitle("Forbidden");
        problem.setProperty("code", "PASSWORD_CHANGE_REQUIRED");
        problem.setProperty("path", request.getRequestURI());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
