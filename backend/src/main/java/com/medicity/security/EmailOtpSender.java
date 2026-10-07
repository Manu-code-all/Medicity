package com.medicity.security;

/** Delivers a sign-in code to an email address. */
public interface EmailOtpSender {

    /** False when no email provider is configured; codes are then never sent. */
    boolean available();

    /**
     * @param email normalised: trimmed, lower case
     * @throws SendFailed if the provider refused or could not be reached
     */
    void send(String email, String code);

    /**
     * Never shown to the caller: codes are sent in the background so that the
     * reply does not depend on whether an account exists (see
     * {@link OtpService#sendToEmail}), which means there is no request left to
     * report this on. It is logged and written to the audit trail instead.
     */
    class SendFailed extends RuntimeException {
        public SendFailed(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
