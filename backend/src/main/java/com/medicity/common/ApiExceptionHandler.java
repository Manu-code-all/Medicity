package com.medicity.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>Two rules govern what reaches the client:
 * <ol>
 *   <li><b>Expected failures</b> ({@link DomainException}) carry their own status,
 *       a stable {@code code}, and a message written for a human. Clients branch
 *       on {@code code}, never on the prose.</li>
 *   <li><b>Unexpected failures</b> return a generic message plus a correlation id
 *       that is also logged. Stack traces, SQL fragments and constraint names are
 *       useful to an attacker mapping the schema, so they never cross the wire.</li>
 * </ol>
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private static final URI ERROR_TYPE = URI.create("https://medicity.dev/errors");

    @ExceptionHandler(DomainException.class)
    public ProblemDetail onDomain(DomainException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        problem.setType(ERROR_TYPE.resolve("/" + e.getCode().toLowerCase().replace('_', '-')));
        problem.setTitle(e.getStatus().getReasonPhrase());
        problem.setProperty("code", e.getCode());
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    /** Bean-validation failures on a request body, reported field by field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setType(ERROR_TYPE.resolve("/validation-failed"));
        problem.setTitle("Bad Request");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onInvalidParam(ConstraintViolationException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more parameters are invalid");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail onAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        // Deliberately identical whether the resource is missing or merely
        // forbidden: distinguishing the two would confirm the existence of other
        // patients' records to anyone probing ids.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have access to this resource");
        problem.setProperty("code", "FORBIDDEN");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail onUnauthenticated(AuthenticationException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication is required");
        problem.setProperty("code", "UNAUTHENTICATED");
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        // The id lets support tie a user's screenshot to one log line without
        // exposing anything about the failure itself.
        String incidentId = UUID.randomUUID().toString();
        log.error("Unhandled exception [incident={}] on {} {}",
                incidentId, request.getMethod(), request.getRequestURI(), e);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Quote this reference if you contact support.");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("incidentId", incidentId);
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}
