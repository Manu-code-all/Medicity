package com.medicity.security;

import com.medicity.common.DomainException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/** Delivers a sign-in code to a mobile number. */
public interface OtpSender {

    /** False when no SMS provider is configured; codes are then never sent. */
    boolean available();

    /**
     * @param phone normalised, +91XXXXXXXXXX
     * @throws SendFailed if the provider refused or could not be reached
     */
    void send(String phone, String code);

    /** 503: the provider's reason is logged, not shown. */
    @Slf4j
    class SendFailed extends DomainException {
        public SendFailed(String reason, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "SMS_FAILED", "We could not send the code just now. Try again, or sign in with email.");
            log.warn("Sign-in code not sent: {}", reason, cause);
        }
    }
}
