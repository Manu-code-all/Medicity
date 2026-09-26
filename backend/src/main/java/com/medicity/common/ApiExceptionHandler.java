package com.medicity.common;

import com.medicity.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
@RequiredArgsConstructor
@Slf4j
public class ApiExceptionHandler {

    private final AuditLog auditLog;

    // Trailing slash matters: URI.resolve replaces the last path segment, so
    // without it "errors" itself would be dropped from every type URI.
    private static final URI ERROR_TYPE = URI.create("https://medicity.dev/errors/");

    @ExceptionHandler(DomainException.class)
    public ProblemDetail onDomain(DomainException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        problem.setType(typeFor(e.getCode()));
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
        problem.setType(typeFor("VALIDATION_FAILED"));
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
        // Authenticated, but the role does not allow this endpoint (for example
        // a doctor calling the patient portal). Row-level denials are recorded
        // where they are decided, with the record id.
        auditLog.recordIndependently("ACCESS_DENIED", "ENDPOINT",
                request.getMethod() + " " + request.getRequestURI(), AuditLog.Outcome.DENIED, null);

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

    /**
     * Spring MVC's own client errors: unknown route (404), wrong method (405),
     * unsupported content type (415), missing parameter (400), and so on.
     *
     * <p>Without this, the catch-all would treat them as crashes and a mistyped
     * URL is reported as a 500 — logged as an incident, and telling the caller
     * the server is broken when the request was. Each of these exceptions
     * already knows its correct status; this only puts it in our error format.
     * Headers are kept because some are part of the contract, such as
     * {@code Allow} on a 405.
     *
     * <p>Called from the catch-all rather than registered as a handler because
     * {@link ErrorResponse} is an interface, which {@code @ExceptionHandler}
     * cannot target; the exceptions implementing it share no common class.
     */
    private static ResponseEntity<ProblemDetail> frameworkError(ErrorResponse e, HttpServletRequest request) {
        HttpStatusCode status = e.getStatusCode();
        String code = status instanceof HttpStatus known ? known.name() : "HTTP_" + status.value();
        ProblemDetail problem = clientError(status, e.getBody().getDetail(), code, request);
        return ResponseEntity.status(status).headers(e.getHeaders()).body(problem);
    }

    /** Body is not valid JSON, or a field has the wrong JSON type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        // The exception message quotes parser internals; the client gets a
        // fixed sentence instead.
        return clientError(HttpStatus.BAD_REQUEST, "The request body could not be read",
                "MALFORMED_REQUEST", request);
    }

    /** A path or query value that cannot be converted, such as a malformed UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, "Parameter '" + e.getName() + "' has an invalid value",
                "INVALID_PARAMETER", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse known) {
            return frameworkError(known, request);
        }

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
        return ResponseEntity.internalServerError().body(problem);
    }

    private static ProblemDetail clientError(HttpStatusCode status, String detail, String code,
                                             HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(typeFor(code));
        if (status instanceof HttpStatus known) {
            problem.setTitle(known.getReasonPhrase());
        }
        problem.setProperty("code", code);
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }

    /** {@code SLOT_ALREADY_BOOKED} becomes {@code https://medicity.dev/errors/slot-already-booked}. */
    private static URI typeFor(String code) {
        return ERROR_TYPE.resolve(code.toLowerCase().replace('_', '-'));
    }
}
