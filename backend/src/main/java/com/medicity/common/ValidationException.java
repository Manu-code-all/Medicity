package com.medicity.common;

import org.springframework.http.HttpStatus;

/** A business rule rejected the request (as distinct from a malformed payload). */
public class ValidationException extends DomainException {

    public ValidationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
