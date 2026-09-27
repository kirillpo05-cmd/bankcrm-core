package com.client360.interaction.persistence;

import com.client360.common.crypto.FieldCipher;
import com.client360.common.web.Cursor;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code interaction.interactions} access (SPEC.md §6.2.2).
 *
 * <p>The only layer that sees ciphertext. {@code subject} is plaintext and searchable;
 * {@code body} is encrypted, which is why there is no full-text search over bodies — a deliberate
 * trade-off of §4.7, not a gap to fill.
 *
 * <p>The timeline is keyset-paginated on {@code (occurred_at DESC, id DESC)}, matching
 * {@code ix_int_client_timeline}. Offsets drift under concurrent writes and degrade into a scan,
 * which is exactly what a feed cannot afford (§4.5).
 */
@Repository
public class InteractionRepository {

    static final String AAD_BODY = "interaction.interactions.body";

    private static final String COLUMNS = """
            id, client_id, type::text AS type, direction::text AS direction, subject,
            body_enc, key_version, occurred_at, duration_seconds,
            outcome::text AS outcome, visibility::text AS visibility, source::text AS source,
            external_ref, author_id, client_owner_id, client_team_id,
            ticket_status::text AS ticket_status, ticket_priority::text AS ticket_priority,
            ticket_assignee_id, sla_due_at, resolved_at, closed_at, resolution_note,
            waiting_since, sla_paused_seconds,
            corrects_id, edited_at, edit_count,
            version, created_at, updated_at, deleted_at, deleted_by, deletion_reason
            """;

    /**
     * IL-BR-09: a private note belongs to its author and to admins. Supervisors do not see it, and
     * the rule is applied in SQL so a private row never leaves the database for someone who may
     * not read it.
     */
    private static final String VISIBLE = """
            AND (visibility <> 'PRIVATE'
                 OR author_id = CAST(:viewerId AS uuid)
                 OR CAST(:viewerIsAdmin AS boolean))
            """;

    private final JdbcClient jdbc;
    private final FieldCipher cipher;

    public InteractionRepository(JdbcClient jdbc, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Interaction insert(NewInteraction draft) {
        return jdbc.sql("""
                        INSERT INTO interactions (
                            id, client_id, type, direction, subject, body_enc, key_version,
                            occurred_at, duration_seconds, outcome, visibility, source,
                            external_ref, author_id, client_owner_id, client_team_id,
                            ticket_status, ticket_priority, ticket_assignee_id, sla_due_at,
                            corrects_id)
                        VALUES (
                            :id, :clientId,
                            CAST(:type AS interaction_type),
                            CAST(:direction AS interaction_direction),
                            :subject, :bodyEnc, :keyVersion,
                            :occurredAt, :durationSeconds,
                            CAST(:outcome AS interaction_outcome),
                            CAST(:visibility AS interaction_visibility),
                            CAST(:source AS interaction_source),
                            :externalRef, :authorId, :clientOwnerId, :clientTeamId,
                            CAST(:ticketStatus AS ticket_status),
                            CAST(:ticketPriority AS ticket_priority),
                            :ticketAssigneeId, :slaDueAt,
                            :correctsId)
                        RETURNING
                        """ + COLUMNS)
                .param("id", draft.id())
                .param("clientId", draft.clientId())
                .param("type", draft.type().name())
                .param("direction", draft.direction().name())
                .param("subject", draft.subject())
                .param("bodyEnc", cipher.encrypt(draft.body(), AAD_BODY))
                .param("keyVersion", cipher.currentKeyVersion())
                .param("occurredAt", timestamp(draft.occurredAt()))
                .param("durationSeconds", draft.durationSeconds())
                .param("outcome", draft.outcome().name())
                .param("visibility", draft.visibility().name())
                .param("source", draft.source().name())
                .param("externalRef", draft.externalRef())
                .param("authorId", draft.authorId())
                // Stamped from the authorization answer this write already made, so a new row is
                // never unscoped and the copy costs no extra hop (see V6).
                .param("clientOwnerId", draft.clientOwnerId())
                .param("clientTeamId", draft.clientTeamId())
                // All four are null together on anything that is not a ticket, which is exactly
                // what ck_interactions_ticket_fields requires. A new ticket always starts at NEW:
                // §6.3 has no way to create one already in progress, and IL-BR-08's transitions
                // only mean something if every ticket enters the machine at the same state.
                .param("ticketStatus", draft.ticket() == null ? null : TicketStatus.NEW.name())
                .param(
                        "ticketPriority",
                        draft.ticket() == null
                                ? null
                                : draft.ticket().priority().name())
                .param(
                        "ticketAssigneeId",
                        draft.ticket() == null ? null : draft.ticket().assigneeId())
                .param(
                        "slaDueAt",
                        draft.ticket() == null ? null : timestamp(draft.ticket().slaDueAt()))
                .param("correctsId", draft.correctsId())
                .query(this::map)
                .single();
    }

    /**
     * The cross-client feed (§6.3 {@code GET /interactions}, IL-US-07) — the supervisor's coaching
     * view, newest first.
     *
     * <p>Scoped by the denormalized {@code client_team_id} (V6), which is what makes this query
     * possible without a client table. {@code PRIVATE} is excluded unconditionally and not through
     * the usual visibility clause: IL-BR-09 keeps private notes out of this feed for everyone,
     * including their own author and an admin. A coaching view is not the place to read them.
     */
    public List<TimelineRow> crossClientFeed(FeedQuery query, int limit) {
        Cursor cursor = query.cursor();
        return jdbc.sql("SELECT " + COLUMNS + """
                         , false AS has_correction
                         , (SELECT count(*) FROM interaction_attachments a
                             WHERE a.interaction_id = interactions.id AND a.deleted_at IS NULL)
                               AS attachment_count
                         FROM interactions
                         WHERE deleted_at IS NULL
                           AND visibility <> 'PRIVATE'
                           AND (CAST(:teamId AS uuid) IS NULL OR client_team_id = CAST(:teamId AS uuid))
                           AND (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))
                           AND (CAST(:authorId AS uuid) IS NULL OR author_id = CAST(:authorId AS uuid))
                           AND (CAST(:types AS text[]) IS NULL OR type::text = ANY (CAST(:types AS text[])))
                           AND (CAST(:fromTs AS timestamptz) IS NULL OR occurred_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL OR occurred_at < CAST(:toTs AS timestamptz))
                           AND (CAST(:cursorTs AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("teamId", query.teamId())
                .param("clientId", query.clientId())
                .param("authorId", query.authorId())
                .param(
                        "types",
                        query.types().isEmpty()
                                ? null
                                : query.types().stream().map(Enum::name).toArray(String[]::new))
                .param("fromTs", timestamp(query.from()))
                .param("toTs", timestamp(query.to()))
                .param("cursorTs", cursor == null ? null : timestamp(cursor.ts()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("limit", limit)
                .query((rs, n) ->
                        new TimelineRow(map(rs, n), rs.getBoolean("has_correction"), rs.getInt("attachment_count")))
                .list();
    }

    /**
     * @param teamId {@code null} only for a caller at {@code ALL} scope who asked for no team
     */
    public record FeedQuery(
            UUID teamId,
            UUID clientId,
            UUID authorId,
            List<InteractionType> types,
            Instant from,
            Instant to,
            Cursor cursor) {

        public FeedQuery {
            types = types == null ? List.of() : List.copyOf(types);
        }
    }

    /**
     * The ticket queue (§6.3 {@code GET /tickets}), most urgent first.
     *
     * <p>Ordered by {@code sla_due_at} ascending to match {@code ix_int_ticket_queue}, which the
     * spec builds on {@code (ticket_assignee_id, sla_due_at)} — the index says plainly that this
     * screen is "what is due next on my desk", and the ordering follows it rather than fighting it.
     *
     * <p>Every filter is a bound parameter against a fixed statement. No string is assembled here:
     * a queue with five optional filters is exactly where dynamic SQL creeps in, and this service
     * holds the ticket history of every client.
     */
    public List<Interaction> ticketQueue(TicketQuery query, int limit) {
        Cursor cursor = query.cursor();
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM interactions
                         WHERE type = 'TICKET'
                           AND deleted_at IS NULL
                           AND (CAST(:assigneeId AS uuid) IS NULL
                                OR ticket_assignee_id = CAST(:assigneeId AS uuid))
                           AND (CAST(:clientId AS uuid) IS NULL OR client_id = CAST(:clientId AS uuid))
                           -- V6: the team's queue, which no amount of REST could answer before.
                           AND (CAST(:teamId AS uuid) IS NULL OR client_team_id = CAST(:teamId AS uuid))
                           AND (CAST(:statuses AS text[]) IS NULL
                                OR ticket_status::text = ANY (CAST(:statuses AS text[])))
                           -- With no explicit status the queue is what is still open. A closed
                           -- ticket is history, and the index excludes it for the same reason.
                           AND (CAST(:statuses AS text[]) IS NOT NULL
                                OR ticket_status NOT IN ('CLOSED', 'REJECTED'))
                           AND (CAST(:priority AS text) IS NULL
                                OR ticket_priority::text = CAST(:priority AS text))
                           -- The same rule as Ticket.isBreached, in SQL: a paused clock is never
                           -- late, and a resolved ticket is judged against when it was resolved.
                           AND (NOT CAST(:breachedOnly AS boolean)
                                OR (ticket_status <> 'WAITING_CLIENT'
                                    AND COALESCE(resolved_at, now()) > sla_due_at))
                           -- IL-EC-10, served by ix_int_ticket_waiting_since. now() is not
                           -- IMMUTABLE, so the age is compared here rather than in the predicate.
                           AND (NOT CAST(:staleOnly AS boolean)
                                OR (ticket_status = 'WAITING_CLIENT'
                                    AND waiting_since < now() - CAST(:staleAfter AS interval)))
                           AND (CAST(:cursorTs AS timestamptz) IS NULL
                                OR (sla_due_at, id) > (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
                        """ + VISIBLE + """
                         ORDER BY sla_due_at ASC, id ASC
                         LIMIT :limit
                        """)
                .param("assigneeId", query.assigneeId())
                .param("clientId", query.clientId())
                .param("teamId", query.teamId())
                .param(
                        "statuses",
                        query.statuses().isEmpty()
                                ? null
                                : query.statuses().stream().map(Enum::name).toArray(String[]::new))
                .param(
                        "priority",
                        query.priority() == null ? null : query.priority().name())
                .param("breachedOnly", query.breachedOnly())
                .param("staleOnly", query.staleOnly())
                .param("staleAfter", Interaction.Ticket.STALE_AFTER.toDays() + " days")
                .param("cursorTs", cursor == null ? null : timestamp(cursor.ts()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("viewerId", query.viewerId())
                .param("viewerIsAdmin", query.viewerIsAdmin())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * Writes a ticket transition (IL-BR-08). Every lifecycle column moves in one statement, because
     * they are not independent: {@code ck_interactions_ticket_resolved_at} ties {@code resolved_at}
     * to the status and {@code ck_interactions_waiting_pair} ties {@code waiting_since} to it too,
     * so a sequence of updates would have to pass through states the database refuses.
     *
     * <p>The service has already derived every value, including the shifted deadline. This layer
     * does no business-hours arithmetic and reads no clock.
     */
    public Interaction updateTicket(UUID id, TicketUpdate update) {
        return jdbc.sql("""
                        UPDATE interactions SET
                            ticket_status = CAST(:status AS ticket_status),
                            ticket_priority = CAST(:priority AS ticket_priority),
                            ticket_assignee_id = :assigneeId,
                            sla_due_at = :slaDueAt,
                            resolved_at = :resolvedAt,
                            closed_at = :closedAt,
                            resolution_note = :resolutionNote,
                            waiting_since = :waitingSince,
                            sla_paused_seconds = :pausedSeconds,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :id AND deleted_at IS NULL
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("status", update.status().name())
                .param("priority", update.priority().name())
                .param("assigneeId", update.assigneeId())
                .param("slaDueAt", timestamp(update.slaDueAt()))
                .param("resolvedAt", timestamp(update.resolvedAt()))
                .param("closedAt", timestamp(update.closedAt()))
                .param("resolutionNote", update.resolutionNote())
                .param("waitingSince", timestamp(update.waitingSince()))
                .param("pausedSeconds", update.pausedSeconds())
                .query(this::map)
                .single();
    }

    /**
     * Rewrites the denormalized ownership of every interaction of one client (V6, CP-US-05).
     *
     * <p>Deleted rows are included on purpose: an auditor's {@code includeDeleted} view filters by
     * the same columns, and leaving them behind would hide exactly the records somebody looking
     * into a reassignment wants to see.
     */
    public int reassignClientScope(UUID clientId, UUID ownerId, UUID teamId) {
        return jdbc.sql("UPDATE interactions SET client_owner_id = :ownerId, client_team_id = :teamId"
                        + " WHERE client_id = :clientId"
                        + "   AND (client_owner_id IS DISTINCT FROM :ownerId"
                        + "        OR client_team_id IS DISTINCT FROM :teamId)")
                .param("clientId", clientId)
                .param("ownerId", ownerId)
                .param("teamId", teamId)
                .update();
    }

    /**
     * Moves every interaction of a merged client to the survivor (CP-BR-10, IL-BR-13).
     *
     * <p>Soft-deleted rows move too. An auditor reconstructing what happened needs the whole
     * history under one client, and a deleted interaction left pointing at a record that no longer
     * answers would be findable by nothing.
     */
    public int repointToSurvivor(UUID mergedClientId, UUID survivorId, UUID survivorOwnerId, UUID survivorTeamId) {
        return jdbc.sql("UPDATE interactions SET client_id = :survivorId,"
                        + " client_owner_id = :ownerId, client_team_id = :teamId"
                        + " WHERE client_id = :mergedClientId")
                .param("survivorId", survivorId)
                .param("ownerId", survivorOwnerId)
                .param("teamId", survivorTeamId)
                .param("mergedClientId", mergedClientId)
                .update();
    }

    public Optional<Interaction> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM interactions WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** IL-BR-12: an import that repeats itself finds the row it already wrote. */
    public Optional<Interaction> findByExternalRef(InteractionSource source, String externalRef) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM interactions
                         WHERE source = CAST(:source AS interaction_source)
                           AND external_ref = :externalRef
                           AND deleted_at IS NULL
                        """)
                .param("source", source.name())
                .param("externalRef", externalRef)
                .query(this::map)
                .optional();
    }

    /**
     * One page of the client's timeline, newest first (IL-US-01). {@code limit} is fetched plus
     * one so the caller can tell whether another page exists without a second count query.
     */
    public List<TimelineRow> timeline(TimelineQuery query, int limit) {
        Cursor cursor = query.cursor();
        return jdbc.sql("SELECT " + COLUMNS + """
                         , EXISTS (SELECT 1 FROM interactions c
                                    WHERE c.corrects_id = interactions.id AND c.deleted_at IS NULL)
                               AS has_correction
                         , (SELECT count(*) FROM interaction_attachments a
                             WHERE a.interaction_id = interactions.id AND a.deleted_at IS NULL)
                               AS attachment_count
                         FROM interactions
                         WHERE client_id = :clientId
                           AND (CAST(:includeDeleted AS boolean) OR deleted_at IS NULL)
                           AND (CAST(:types AS text[]) IS NULL OR type::text = ANY (CAST(:types AS text[])))
                           AND (CAST(:fromTs AS timestamptz) IS NULL OR occurred_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL OR occurred_at < CAST(:toTs AS timestamptz))
                           AND (CAST(:authorId AS uuid) IS NULL OR author_id = CAST(:authorId AS uuid))
                           AND (CAST(:subjectLike AS text) IS NULL
                                OR lower(subject) LIKE CAST(:subjectLike AS text) ESCAPE '\\')
                           AND (CAST(:cursorTs AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
                        """ + VISIBLE + """
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("clientId", query.clientId())
                .param("includeDeleted", query.includeDeleted())
                .param(
                        "types",
                        query.types().isEmpty()
                                ? null
                                : query.types().stream().map(Enum::name).toArray(String[]::new))
                .param("fromTs", timestamp(query.from()))
                .param("toTs", timestamp(query.to()))
                .param("authorId", query.authorId())
                .param("subjectLike", subjectPattern(query.subjectQuery()))
                .param("cursorTs", cursor == null ? null : timestamp(cursor.ts()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("viewerId", query.viewerId())
                .param("viewerIsAdmin", query.viewerIsAdmin())
                .param("limit", limit)
                .query((rs, n) ->
                        new TimelineRow(map(rs, n), rs.getBoolean("has_correction"), rs.getInt("attachment_count")))
                .list();
    }

    /**
     * IL-BR-01/02: the author rewrites the wording, inside the window, of a row that has not moved.
     *
     * <p>The window is in the {@code WHERE} clause, measured by PostgreSQL's clock, not only checked
     * in Java beforehand. An edit that starts at 14:59 and commits at 15:01 must not land, and the
     * only clock that decides that the same way for every instance is the database's (rule 7).
     * Empty means one of those conditions failed; the caller re-reads to say which.
     */
    public Optional<Interaction> updateContent(
            UUID id, String subject, String body, int expectedVersion, UUID authorId) {
        return jdbc.sql("""
                        UPDATE interactions SET
                            subject     = :subject,
                            body_enc    = :bodyEnc,
                            key_version = :keyVersion,
                            edited_at   = now(),
                            edit_count  = edit_count + 1,
                            version     = version + 1,
                            updated_at  = now()
                        WHERE id = :id
                          AND version = :expectedVersion
                          AND author_id = :authorId
                          AND deleted_at IS NULL
                          AND created_at > now() - INTERVAL '15 minutes'
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("subject", subject)
                .param("bodyEnc", cipher.encrypt(body, AAD_BODY))
                .param("keyVersion", cipher.currentKeyVersion())
                .param("expectedVersion", expectedVersion)
                .param("authorId", authorId)
                .query(this::map)
                .optional();
    }

    /**
     * §6.3 soft delete. The row stays — audit rows reference it, corrections may point at it — and
     * disappears from every read except an auditor's {@code includeDeleted} timeline, where it is
     * shown struck through with who removed it and why.
     */
    public Optional<Interaction> softDelete(UUID id, int expectedVersion, UUID actorId, String reason) {
        return jdbc.sql("""
                        UPDATE interactions SET
                            deleted_at      = now(),
                            deleted_by      = :actor,
                            deletion_reason = :reason,
                            version         = version + 1,
                            updated_at      = now()
                        WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                        RETURNING
                        """ + COLUMNS)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("actor", actorId)
                .param("reason", reason)
                .query(this::map)
                .optional();
    }

    /**
     * §6.3 {@code q}: a substring of the subject — bodies are encrypted and not searchable (§4.7).
     * The caller's text is escaped, so a {@code %} they type is a percent sign rather than a
     * wildcard, and it is a bound parameter either way. Served by {@code ix_int_subject_trgm}.
     */
    static String subjectPattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** IL-BR-03: every correction of one original, oldest first — the order they were made in. */
    public List<Interaction> findCorrections(UUID originalId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM interactions WHERE corrects_id = :id AND deleted_at IS NULL ORDER BY created_at, id")
                .param("id", originalId)
                .query(this::map)
                .list();
    }

    private Interaction map(ResultSet rs, int rowNum) throws SQLException {
        short keyVersion = rs.getShort("key_version");
        return new Interaction(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                InteractionType.valueOf(rs.getString("type")),
                InteractionDirection.valueOf(rs.getString("direction")),
                rs.getString("subject"),
                cipher.decrypt(rs.getBytes("body_enc"), keyVersion, AAD_BODY),
                instant(rs, "occurred_at"),
                rs.getObject("duration_seconds", Integer.class),
                InteractionOutcome.valueOf(rs.getString("outcome")),
                InteractionVisibility.valueOf(rs.getString("visibility")),
                InteractionSource.valueOf(rs.getString("source")),
                rs.getString("external_ref"),
                rs.getObject("author_id", UUID.class),
                mapTicket(rs),
                rs.getObject("corrects_id", UUID.class),
                instant(rs, "edited_at"),
                rs.getInt("edit_count"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "deleted_at"),
                rs.getObject("deleted_by", UUID.class),
                rs.getString("deletion_reason"));
    }

    /**
     * The subtype, or {@code null} on everything that is not a ticket.
     *
     * <p>{@code ticket_status} is the discriminator rather than {@code type}, because
     * {@code ck_interactions_ticket_fields} makes the two equivalent and the status is the column
     * actually being read. A row where they disagree cannot exist.
     */
    private static Interaction.Ticket mapTicket(ResultSet rs) throws SQLException {
        String status = rs.getString("ticket_status");
        if (status == null) {
            return null;
        }
        return new Interaction.Ticket(
                TicketStatus.valueOf(status),
                TicketPriority.valueOf(rs.getString("ticket_priority")),
                rs.getObject("ticket_assignee_id", UUID.class),
                instant(rs, "sla_due_at"),
                instant(rs, "resolved_at"),
                instant(rs, "closed_at"),
                rs.getString("resolution_note"),
                instant(rs, "waiting_since"),
                rs.getLong("sla_paused_seconds"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    /**
     * A validated interaction, with the body already PAN-masked (IL-EC-05).
     *
     * @param ticket the subtype, non-null exactly when {@code type} is {@code TICKET}. The service
     *     has already derived {@code slaDueAt} from the priority in the owning team's business
     *     hours (IL-BR-07) — this layer stores what it is given and never computes a deadline.
     */
    public record NewInteraction(
            UUID id,
            UUID clientId,
            InteractionType type,
            InteractionDirection direction,
            String subject,
            String body,
            Instant occurredAt,
            Integer durationSeconds,
            InteractionOutcome outcome,
            InteractionVisibility visibility,
            InteractionSource source,
            String externalRef,
            UUID authorId,
            UUID clientOwnerId,
            UUID clientTeamId,
            NewTicket ticket,
            UUID correctsId) {}

    /** The ticket fields at creation. Status starts at {@code NEW} and is not the caller's to set. */
    public record NewTicket(TicketPriority priority, UUID assigneeId, Instant slaDueAt) {}

    /**
     * @param breachedOnly {@code ?slaBreached=true} — the supervisor's "what did we miss" view
     * @param staleOnly {@code ?stale=true} — IL-EC-10, the tickets a breach filter never shows
     * @param viewerIsAdmin private notes never reach a queue that is not theirs (IL-BR-09); a
     *     ticket is rarely private, but the rule is applied in SQL here for the same reason it is
     *     on the timeline — a row that may not be read should not leave the database
     */
    public record TicketQuery(
            UUID assigneeId,
            UUID clientId,
            UUID teamId,
            List<TicketStatus> statuses,
            TicketPriority priority,
            boolean breachedOnly,
            boolean staleOnly,
            Cursor cursor,
            UUID viewerId,
            boolean viewerIsAdmin) {

        public TicketQuery {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }
    }

    /** Every lifecycle column after a transition, already resolved by the service. */
    public record TicketUpdate(
            TicketStatus status,
            TicketPriority priority,
            UUID assigneeId,
            Instant slaDueAt,
            Instant resolvedAt,
            Instant closedAt,
            String resolutionNote,
            Instant waitingSince,
            long pausedSeconds) {}

    /**
     * A timeline row with the two facts the feed shows about it that live on other rows (§6.3).
     *
     * @param hasCorrection another interaction corrects this one, so the feed renders the pair
     *     together with this one visibly superseded (IL-BR-03)
     */
    public record TimelineRow(Interaction interaction, boolean hasCorrection, int attachmentCount) {}

    /**
     * @param viewerIsAdmin the one role that sees private notes (IL-BR-09); resolved from a
     *     permission, never from a role string, before it reaches this layer
     */
    public record TimelineQuery(
            UUID clientId,
            List<InteractionType> types,
            Instant from,
            Instant to,
            UUID authorId,
            String subjectQuery,
            boolean includeDeleted,
            Cursor cursor,
            UUID viewerId,
            boolean viewerIsAdmin) {

        public TimelineQuery {
            types = types == null ? List.of() : List.copyOf(types);
        }
    }
}
