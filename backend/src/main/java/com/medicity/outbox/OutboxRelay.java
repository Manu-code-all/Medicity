package com.medicity.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Delivers outbox events to their consumers.
 *
 * <p><b>Many instances, no coordination.</b> Each event is claimed with
 * {@code FOR UPDATE SKIP LOCKED}: an instance locks the oldest due event, and
 * any other instance polling at the same moment skips that row and takes the
 * next. Two relays never process one event at once, and neither waits for the
 * other. (The scheduled jobs use an advisory lock instead, because there the
 * whole job must run once; here the work splits into independent pieces.)
 *
 * <p><b>One transaction per event.</b> The consumer's writes and the
 * "published" mark commit together. If the consumer fails, that transaction
 * rolls back and a second one records the failure, so one bad event cannot
 * block or undo the others.
 *
 * <p><b>Retries.</b> A failed event is retried with exponential backoff
 * (5 s, 10 s, 20 s ... capped at an hour). After {@value #MAX_ATTEMPTS}
 * failures it is marked failed and left for a person: an event that fails
 * forever would otherwise be retried forever.
 */
@Component
@Slf4j
public class OutboxRelay {

    static final int MAX_ATTEMPTS = 10;
    static final int MAX_PER_RUN = 200;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final List<OutboxConsumer> consumers;
    private final ObjectMapper json;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactions, List<OutboxConsumer> consumers,
                       ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.consumers = consumers;
        this.json = json;
        this.clock = clock;
        this.published = metrics.counter("medicity.outbox.published");
        this.failures = metrics.counter("medicity.outbox.failures");
        // A growing backlog is the first sign the relay is stuck or failing.
        Gauge.builder("medicity.outbox.pending", () -> jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL", Long.class))
                .description("Outbox events waiting to be delivered")
                .register(metrics);
    }

    private record Claim(long id, int attempts) {}

    /** @return how many events were delivered. */
    @Scheduled(fixedDelayString = "${medicity.outbox.poll-interval:PT2S}")
    public int drain() {
        int delivered = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            try {
                if (transactions.execute(tx -> deliverOne()) == null) {
                    break;                          // nothing due
                }
                delivered++;
                published.increment();              // after commit, like the booking counter
            } catch (DeliveryFailed failed) {
                // The event's transaction rolled back, undoing anything the
                // consumer wrote; record the failure in a new one.
                recordFailure(failed.claim, String.valueOf(failed.getCause()));
            }
        }
        return delivered;
    }

    /** @return the delivered event's claim, or null if no event is due. */
    private Claim deliverOne() {
        List<Claim> claimed = jdbc.query("""
                SELECT id, attempts FROM outbox_events
                WHERE published_at IS NULL AND failed_at IS NULL AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new Claim(rs.getLong("id"), rs.getInt("attempts")),
                Timestamp.from(clock.instant()));
        if (claimed.isEmpty()) {
            return null;
        }
        Claim claim = claimed.get(0);
        OutboxEvent event = jdbc.queryForObject(
                "SELECT event_id, event_type, aggregate_id, payload::text AS payload FROM outbox_events WHERE id = ?",
                (rs, n) -> new OutboxEvent(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class), parse(rs.getString("payload"))),
                claim.id());

        try {
            boolean handled = false;
            for (OutboxConsumer consumer : consumers) {
                handled |= consumer.handle(event);
            }
            if (!handled) {
                throw new IllegalStateException("No consumer for event type " + event.type());
            }
            jdbc.update("UPDATE outbox_events SET published_at = ?, last_error = NULL WHERE id = ?",
                    Timestamp.from(clock.instant()), claim.id());
            return claim;
        } catch (RuntimeException e) {
            throw new DeliveryFailed(claim, e);     // rolls this event's transaction back
        }
    }

    private void recordFailure(Claim claim, String cause) {
        failures.increment();
        int attempts = claim.attempts() + 1;
        boolean exhausted = attempts >= MAX_ATTEMPTS;
        Duration backoff = FIRST_RETRY.multipliedBy(1L << Math.min(attempts - 1, 20));
        Duration wait = backoff.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : backoff;
        String error = cause.length() > 500 ? cause.substring(0, 500) : cause;

        transactions.executeWithoutResult(tx -> jdbc.update("""
                UPDATE outbox_events
                SET attempts = ?, last_error = ?, next_attempt_at = ?, failed_at = ?
                WHERE id = ?
                """, attempts, error, Timestamp.from(clock.instant().plus(wait)),
                exhausted ? Timestamp.from(clock.instant()) : null, claim.id()));
        if (exhausted) {
            log.error("Outbox event {} failed {} times and was set aside: {}", claim.id(), attempts, error);
        } else {
            log.warn("Outbox event {} failed (attempt {}), retrying in {}: {}", claim.id(), attempts, wait, error);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored outbox payload is not JSON", e);
        }
    }

    /** Carries the claim out of the rolled-back transaction so the failure can be recorded. */
    private static final class DeliveryFailed extends RuntimeException {
        private final Claim claim;

        DeliveryFailed(Claim claim, Throwable cause) {
            super(cause);
            this.claim = claim;
        }
    }
}
