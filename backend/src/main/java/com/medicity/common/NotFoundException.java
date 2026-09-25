package com.medicity.common;

import org.springframework.http.HttpStatus;

public class NotFoundException extends DomainException {

    public NotFoundException(String what, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " was not found");
    }
}
