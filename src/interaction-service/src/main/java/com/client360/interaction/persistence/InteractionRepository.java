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
            version, created_at, updated_at, deleted_at
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
    public List<Interaction> timeline(TimelineQuery query, int limit) {
        Cursor cursor = query.cursor();
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM interactions
                         WHERE client_id = :clientId
                           AND deleted_at IS NULL
                           AND (CAST(:type AS text) IS NULL OR type::text = CAST(:type AS text))
                           AND (CAST(:cursorTs AS timestamptz) IS NULL
                                OR (occurred_at, id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid)))
                        """ + VISIBLE + """
                         ORDER BY occurred_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("clientId", query.clientId())
                .param("type", query.type() == null ? null : query.type().name())
                .param("cursorTs", cursor == null ? null : timestamp(cursor.ts()))
                .param("cursorId", cursor == null ? null : cursor.id())
                .param("viewerId", query.viewerId())
                .param("viewerIsAdmin", query.viewerIsAdmin())
                .param("limit", limit)
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
                instant(rs, "deleted_at"));
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
     * @param viewerIsAdmin the one role that sees private notes (IL-BR-09); resolved from a
     *     permission, never from a role string, before it reaches this layer
     */
    public record TimelineQuery(
            UUID clientId, InteractionType type, Cursor cursor, UUID viewerId, boolean viewerIsAdmin) {}
}
