package com.medicity.notification.email;

import java.util.Map;

/** Delivers one notification email. Tests replace it with a recording fake. */
public interface EmailSender {

    /** False when no provider is configured; nothing is then emailed and nothing is queued. */
    boolean available();

    /** @throws SendFailed if the provider refused or could not be reached */
    void send(Message message);

    /**
     * @param to      the recipient's address
     * @param headers extra headers, such as {@code List-Unsubscribe}; may be empty
     */
    record Message(String to, String subject, String text, String html, Map<String, String> headers) {}

    class SendFailed extends RuntimeException {
        public SendFailed(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
