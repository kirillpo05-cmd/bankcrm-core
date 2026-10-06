package com.client360.audit.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The query of {@code GET /audit} (SPEC.md §8.3, AT-US-01/02/05).
 *
 * @param actions repeatable; an unknown value is a {@code 400} at the edge rather than a filter
 *     that silently matches nothing
 */
public record AuditQuery(
        UUID clientId,
        UUID actorId,
        String entityType,
        UUID entityId,
        List<String> actions,
        String service,
        Instant from,
        Instant to) {

    /** AT-EC-14: a range wider than this is not a filter, it is a scan with a date on it. */
    public static final Duration MAX_RANGE = Duration.ofDays(90);

    public AuditQuery {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * No filter at all — valid for an **export** and not for a search.
     *
     * <p>AT-EC-14 refuses an unfiltered search because seven years of rows cannot be paged through
     * interactively. An export is the case that legitimately wants a wide slice: it streams to a
     * file and is bounded instead by the row ceiling and the seven-year range (§8.3). Different
     * question, different guard.
     */
    public static AuditQuery unfiltered() {
        return new AuditQuery(null, null, null, null, List.of(), null, null, null);
    }

    /**
     * The filter as one line, for the self-audit entry a search writes about itself (AT-BR-10).
     *
     * <p>Identifiers and ranges only, because that is all a filter is here — there is no free-text
     * field to leak. What it deliberately does not carry is the result: an audit of a search must
     * not become a second copy of the thing searched (AT-BR-04).
     */
    public String summary() {
        List<String> parts = new java.util.ArrayList<>();
        if (clientId != null) {
            parts.add("clientId=" + clientId);
        }
        if (actorId != null) {
            parts.add("actorId=" + actorId);
        }
        if (entityType != null) {
            parts.add("entityType=" + entityType);
        }
        if (entityId != null) {
            parts.add("entityId=" + entityId);
        }
        if (!actions.isEmpty()) {
            parts.add("action=" + String.join("|", actions));
        }
        if (service != null) {
            parts.add("service=" + service);
        }
        if (from != null) {
            parts.add("from=" + from);
        }
        if (to != null) {
            parts.add("to=" + to);
        }
        return parts.isEmpty() ? "none" : String.join(" ", parts);
    }

    /**
     * AT-EC-14: seven years of log is not something to walk because a filter was left empty.
     *
     * <p>An identifier is enough on its own — it bounds the result by construction. A time range
     * alone has to be narrow, because "everything in the last decade" is the query this rule exists
     * to refuse.
     */
    public boolean isSufficientlyFiltered() {
        if (clientId != null || actorId != null || entityId != null) {
            return true;
        }
        return from != null
                && to != null
                && !Duration.between(from, to).minus(MAX_RANGE).isPositive();
    }
}
