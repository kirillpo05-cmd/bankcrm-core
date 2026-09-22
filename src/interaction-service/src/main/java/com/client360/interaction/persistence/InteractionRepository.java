package com.client360.interaction.persistence;

import com.client360.common.crypto.FieldCipher;
import com.client360.common.web.Cursor;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.InteractionVisibility;
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
            external_ref, author_id, corrects_id, edited_at, edit_count,
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
                            external_ref, author_id, corrects_id)
                        VALUES (
                            :id, :clientId,
                            CAST(:type AS interaction_type),
                            CAST(:direction AS interaction_direction),
                            :subject, :bodyEnc, :keyVersion,
                            :occurredAt, :durationSeconds,
                            CAST(:outcome AS interaction_outcome),
                            CAST(:visibility AS interaction_visibility),
                            CAST(:source AS interaction_source),
                            :externalRef, :authorId, :correctsId)
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
                .param("correctsId", draft.correctsId())
                .query(this::map)
                .single();
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(java.time.ZoneOffset.UTC);
    }

    /** A validated interaction, with the body already PAN-masked (IL-EC-05). */
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
            UUID correctsId) {}

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
