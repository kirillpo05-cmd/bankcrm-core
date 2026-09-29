package com.client360.audit.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /audit} (SPEC.md §8.3).
 *
 * @param lagMs how far behind the pipeline was when this entry was stored (AT-BR-07). Served rather
 *     than left to the reader to subtract, because the point of keeping both clocks is that a
 *     delayed entry reads as delayed instead of silently back-dated
 * @param changedFields what changed, never what it changed to for a sensitive field — the values
 *     were already {@code ***MASKED***} when they arrived (AR-01, AT-BR-04)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditEntryView(
        UUID id,
        Instant occurredAt,
        Instant recordedAt,
        long lagMs,
        Actor actor,
        String service,
        Entity entity,
        UUID clientId,
        String action,
        JsonNode changedFields,
        JsonNode context,
        UUID requestId,
        int chainId,
        long chainSeq) {

    public record Actor(UUID id, String email, String role, String ip) {}

    public record Entity(String type, UUID id) {}
}
