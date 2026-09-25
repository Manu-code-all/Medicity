package com.medicity.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for errors that are part of the API contract, as opposed to bugs.
 *
 * <p>Each carries the HTTP status and a stable machine-readable {@code code}
 * so clients can branch on the failure without string-matching the message.
 */
@Getter
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
