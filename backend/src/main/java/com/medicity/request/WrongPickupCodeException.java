package com.medicity.request;

import com.medicity.common.DomainException;
import org.springframework.http.HttpStatus;

/** The code typed at the counter is not the patient's. The attempt has been counted. */
public class WrongPickupCodeException extends DomainException {

    public WrongPickupCodeException(int attemptsLeft) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "WRONG_PICKUP_CODE", attemptsLeft == 0
                ? "Wrong code. No attempts left: the patient can cancel and reserve again."
                : "Wrong code. %d %s left.".formatted(attemptsLeft, attemptsLeft == 1 ? "attempt" : "attempts"));
    }
}
