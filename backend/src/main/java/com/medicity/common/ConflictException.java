package com.medicity.common;

import org.springframework.http.HttpStatus;

/** The request was well-formed but lost a race, or clashes with current state. */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
