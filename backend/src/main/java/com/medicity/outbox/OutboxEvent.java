package com.medicity.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/** An event as the relay hands it to a consumer. */
public record OutboxEvent(UUID eventId, String type, UUID aggregateId, JsonNode payload) {}
