package com.medicity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns 401 for requests that arrive without usable credentials.
 *
 * <p>This exists because of a non-obvious Spring Security default. When a
 * filter chain configures no interactive authentication mechanism — no
 * {@code httpBasic()}, no {@code formLogin()}, as is correct for a pure bearer
 * token API — Spring falls back to {@code Http403ForbiddenEntryPoint}. Every
 * unauthenticated request then gets 403 "forbidden" rather than 401
 * "unauthenticated".
 *
 * <p>That is not a cosmetic difference. 403 means "you are known and still not
 * allowed"; 401 means "authenticate and try again". Clients act on the
 * distinction: the frontend refreshes an expired token on 401 and gives up on
 * 403, so with the default in place an expired session would never recover on
 * its own.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication is required");
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "UNAUTHENTICATED");
        problem.setProperty("path", request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
