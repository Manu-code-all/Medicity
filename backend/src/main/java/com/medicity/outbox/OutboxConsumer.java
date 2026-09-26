package com.medicity.outbox;

/**
 * Acts on outbox events. Runs inside the relay's transaction for that event,
 * so a consumer's database writes commit together with the event being marked
 * published.
 *
 * <p>Must be idempotent: delivery is at least once. A consumer that sends
 * something outside the database (an email, an SMS) cannot be rolled back and
 * should dedupe on {@link OutboxEvent#eventId()} itself.
 */
public interface OutboxConsumer {

    /** @return whether this consumer handled the event type. */
    boolean handle(OutboxEvent event);
}
