package com.medicity.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Refused by a rate limit; {@code retryAfterSeconds} becomes the {@code Retry-After} header. */
@Getter
public class TooManyRequestsException extends DomainException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
