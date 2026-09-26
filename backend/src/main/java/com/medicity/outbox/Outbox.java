package com.medicity.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records that something happened which someone must be told about.
 *
 * <p>{@code MANDATORY}: the event is written in the transaction of the change
 * it describes, never on its own. That is the whole point of an outbox: the
 * alternative, sending the notification directly after committing, loses it
 * if the process dies in between, and sending it before committing announces
 * changes that may still roll back.
 *
 * <p>The payload carries what the consumer needs to act without reading the
 * current state back, because by the time it runs that state may have
 * changed (the appointment cancelled, the prescription corrected again).
 */
@Component
public class Outbox {

    public static final String APPOINTMENT_BOOKED = "APPOINTMENT_BOOKED";
    public static final String APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";
    public static final String PRESCRIPTION_ISSUED = "PRESCRIPTION_ISSUED";
    public static final String PRESCRIPTION_CORRECTED = "PRESCRIPTION_CORRECTED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String type, UUID aggregateId, Map<String, ?> payload) {
        try {
            jdbc.update("INSERT INTO outbox_events (event_type, aggregate_id, payload) VALUES (?, ?, CAST(? AS jsonb))",
                    type, aggregateId, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // Unlike an audit detail, an event cannot be written without its
            // payload; failing here rolls back the change with it.
            throw new IllegalStateException("Could not serialise " + type + " event", e);
        }
    }
}
