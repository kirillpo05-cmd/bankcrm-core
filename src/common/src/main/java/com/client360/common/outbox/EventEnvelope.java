package com.client360.common.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical envelope carried by every message on every topic (SPEC.md §3.3).
 *
 * <p>{@code payload.changedFields} never holds a sensitive value (AR-01) — {@link ChangedFields}
 * masks at construction, so plaintext never reaches the outbox table, let alone the broker.
 * {@code payload.context} carries what {@code audit_log.context} stores: endpoint, reason,
 * break-glass grant id, the attempted permission on a denial.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String service,
        Actor actor,
        EntityRef entity,
        UUID clientId,
        UUID requestId,
        UUID correlationId,
        Payload payload) {

    public record Actor(UUID userId, String email, String role, String ip, String userAgent) {}

    public record EntityRef(String type, UUID id) {}

    public record Payload(String action, Map<String, Change> changedFields, Map<String, Object> context) {}

    /** One field's transition. Sensitive values appear only as {@link ChangedFields#MASKED}. */
    public record Change(Object old, @JsonProperty("new") Object next) {}
}
