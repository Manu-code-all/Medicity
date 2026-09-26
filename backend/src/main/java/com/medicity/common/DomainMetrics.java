package com.medicity.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Business counters, served with the JVM and HTTP metrics at
 * {@code /actuator/prometheus} (ADMIN only).
 *
 * <p>HTTP metrics already say how many requests a route got and how long they
 * took. They cannot say how many bookings lost a race versus succeeded, or how
 * many logins were throttled: both are a 4xx on the same route. These can.
 *
 * <p>Outcomes are a small fixed set of tag values. A tag like the email or the
 * slot id would create a new time series per value, which is how a metrics
 * backend runs out of memory.
 */
@Component
public class DomainMetrics {

    private final MeterRegistry registry;
    private final Counter refreshTokenReuse;
    private final Counter idempotentReplays;

    public DomainMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.refreshTokenReuse = Counter.builder("medicity.refresh.token.reuse")
                .description("Spent refresh tokens presented again; each one ended a session")
                .register(registry);
        this.idempotentReplays = Counter.builder("medicity.idempotency.replays")
                .description("Retried writes answered from a stored response instead of running again")
                .register(registry);
    }

    /**
     * Counted once the booking has committed. Counting at the insert would
     * include bookings whose transaction then rolled back, and the metric would
     * disagree with the database.
     */
    public void bookingCommitted() {
        afterCommit(() -> booking("booked"));
    }

    /** A booking refused with {@code code}, such as {@code SLOT_ALREADY_BOOKED}. */
    public void bookingRefused(String code) {
        booking(code.toLowerCase());
    }

    /** {@code success}, {@code failed}, {@code throttled} or {@code disabled}. */
    public void login(String outcome) {
        Counter.builder("medicity.logins")
                .description("Login attempts by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void refreshTokenReused() {
        refreshTokenReuse.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    private void booking(String outcome) {
        Counter.builder("medicity.bookings")
                .description("Booking attempts by outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
