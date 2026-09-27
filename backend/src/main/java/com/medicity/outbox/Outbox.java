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
    public static final String STORE_REGISTERED = "STORE_REGISTERED";
    public static final String STORE_VERIFIED = "STORE_VERIFIED";
    public static final String DOCTOR_REGISTERED = "DOCTOR_REGISTERED";
    public static final String DOCTOR_VERIFIED = "DOCTOR_VERIFIED";
    public static final String MEDICINE_REQUEST_CREATED = "MEDICINE_REQUEST_CREATED";
    public static final String STORE_ANSWERED = "STORE_ANSWERED";
    public static final String RESERVATION_MADE = "RESERVATION_MADE";
    public static final String RESERVATION_CANCELLED = "RESERVATION_CANCELLED";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String RESERVATION_COLLECTED = "RESERVATION_COLLECTED";
    public static final String MEDICINE_RUNNING_OUT = "MEDICINE_RUNNING_OUT";
    public static final String COURSE_ENDING = "COURSE_ENDING";

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

    /**
     * As {@link #publish}, but at most once per {@code eventId}, however often
     * it is called. For events a job derives from state rather than from a
     * change: the job may see the same state on every run, and the id (derived
     * from what the event is about) makes the second run's publish a no-op.
     *
     * @return whether this call wrote the event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean publishOnce(String type, UUID eventId, UUID aggregateId, Map<String, ?> payload) {
        try {
            return jdbc.update("""
                    INSERT INTO outbox_events (event_id, event_type, aggregate_id, payload)
                    VALUES (?, ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (event_id) DO NOTHING
                    """, eventId, type, aggregateId, json.writeValueAsString(payload)) == 1;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + type + " event", e);
        }
    }
}
