package com.medicity.common;

import org.springframework.http.HttpStatus;

/** The request itself is malformed in a way bean validation cannot express, such as a bad header. */
public class BadRequestException extends DomainException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
