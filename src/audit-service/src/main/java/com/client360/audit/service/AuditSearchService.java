package com.client360.audit.service;

import com.client360.audit.api.AuditEntryView;
import com.client360.audit.api.AuditQuery;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.web.Cursor;
import com.client360.common.web.KeysetPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /audit} — searching the log (SPEC.md §8.3, AT-US-01/02/05).
 *
 * <p>Keyset pagination only, and every filter a bound parameter against one fixed statement. No
 * string is assembled anywhere in this service: it holds seven years of who-did-what for every
 * customer, and a query builder is exactly where an injection would eventually get in.
 */
@Service
public class AuditSearchService {

    /** §8.3. The ceiling matters: a page is streamed to an auditor, not to a machine. */
    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    /**
     * The whitelist of {@code action} values, from §8.2.1. Checked here rather than cast blindly,
     * because an unknown value reaching {@code CAST(… AS audit_action)} is a 500 where the honest
     * answer is a 400 naming the field.
     */
    private static final Set<String> ACTIONS = Set.of(
            "CREATE",
            "UPDATE",
            "DELETE",
            "MERGE",
            "READ_SENSITIVE",
            "EXPORT",
            "LOGIN_SUCCESS",
            "LOGIN_FAILURE",
            "LOGOUT",
            "TOKEN_REFRESH",
            "PERMISSION_DENIED",
            "ROLE_CHANGE",
            "ACCESS_GRANT",
            "ACCESS_REVOKE");

    private final JdbcClient jdbc;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    public AuditSearchService(JdbcClient jdbc, AccessPolicy accessPolicy, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessPolicy = accessPolicy;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public KeysetPage<AuditEntryView> search(AuditQuery query, String cursor, Integer limit, CurrentUser caller) {
        authorize(caller);
        validate(query);

        int pageSize = pageSize(limit);
        Cursor decoded = Cursor.decode(cursor);
        List<AuditEntryView> rows = jdbc.sql("""
                        SELECT id, occurred_at, recorded_at, actor_id, actor_email, actor_role,
                               host(actor_ip) AS actor_ip, service, entity_type, entity_id, client_id,
                               action::text AS action, changed_fields, context, request_id,
                               chain_id, chain_seq
                          FROM audit_log
                         WHERE (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))
                           AND (CAST(:actorId AS uuid) IS NULL OR actor_id = CAST(:actorId AS uuid))
                           AND (CAST(:entityType AS text) IS NULL OR entity_type = CAST(:entityType AS text))
                           AND (CAST(:entityId AS uuid) IS NULL OR entity_id = CAST(:entityId AS uuid))
                           AND (CAST(:actions AS text[]) IS NULL
                                OR action::text = ANY (CAST(:actions AS text[])))
                           AND (CAST(:service AS text) IS NULL OR service = CAST(:service AS text))
                           AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz))
                           AND (CAST(:cursorTs AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("clientId", query.clientId())
                .param("actorId", query.actorId())
                .param("entityType", query.entityType())
                .param("entityId", query.entityId())
                .param(
                        "actions",
                        query.actions().isEmpty() ? null : query.actions().toArray(String[]::new))
                .param("service", query.service())
                .param("from", timestamp(query.from()))
                .param("to", timestamp(query.to()))
                .param("cursorTs", decoded == null ? null : timestamp(decoded.ts()))
                .param("cursorId", decoded == null ? null : decoded.id())
                .param("limit", pageSize + 1)
                .query(this::map)
                .list();

        boolean hasMore = rows.size() > pageSize;
        List<AuditEntryView> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? new Cursor(page.getLast().occurredAt(), page.getLast().id()).encode()
                : null;
        return new KeysetPage<>(page, nextCursor, hasMore);
    }

    /**
     * AT-BR-11. Managers hold {@code audit:read} at no scope and are refused by that alone — the
     * check never mentions a role (rule 5).
     *
     * <p>{@code TEAM} is refused for now, and not because supervisors should not have it. This
     * service owns no client table, so "clients on their team" cannot be resolved here; answering
     * it needs client-service to be asked per query, which is a cross-service dependency on the
     * read path of an evidence store and a decision worth making deliberately rather than in
     * passing. Refusing is the honest interim: a supervisor is told no, rather than shown rows that
     * were never scope-checked.
     */
    private void authorize(CurrentUser caller) {
        Scope scope = accessPolicy
                .scopeOf(caller, Permissions.AUDIT_READ)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.ROLE_REQUIRED, "Reading the audit log requires the audit:read permission.")
                        .detail("permission", Permissions.AUDIT_READ));
        if (scope != Scope.ALL) {
            throw ApiException.forbidden(
                            ErrorCodes.SCOPE_VIOLATION,
                            "Team-scoped audit search is not available; audit:read at ALL scope is required.")
                    .detail("permission", Permissions.AUDIT_READ, "scope", scope);
        }
    }

    private void validate(AuditQuery query) {
        if (!query.isSufficientlyFiltered()) {
            throw ApiException.validation(
                            "filter",
                            "at least one of clientId, actorId, entityId, or a from/to range under "
                                    + AuditQuery.MAX_RANGE.toDays() + " days")
                    .detail("maxRangeDays", AuditQuery.MAX_RANGE.toDays());
        }
        query.actions().stream()
                .filter(action -> !ACTIONS.contains(action))
                .findFirst()
                .ifPresent(unknown -> {
                    throw ApiException.validation("action", "unknown action").detail("action", unknown);
                });
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private AuditEntryView map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
        OffsetDateTime recordedAt = rs.getObject("recorded_at", OffsetDateTime.class);
        return new AuditEntryView(
                rs.getObject("id", UUID.class),
                occurredAt.toInstant(),
                recordedAt.toInstant(),
                Duration.between(occurredAt.toInstant(), recordedAt.toInstant()).toMillis(),
                new AuditEntryView.Actor(
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_email"),
                        rs.getString("actor_role"),
                        rs.getString("actor_ip")),
                rs.getString("service"),
                new AuditEntryView.Entity(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class)),
                rs.getObject("client_id", UUID.class),
                rs.getString("action"),
                json(rs.getString("changed_fields")),
                json(rs.getString("context")),
                rs.getObject("request_id", UUID.class),
                rs.getInt("chain_id"),
                rs.getLong("chain_seq"));
    }

    private JsonNode json(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime timestamp(java.time.Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
