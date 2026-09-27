package com.client360.interaction.persistence;

import com.client360.interaction.domain.Attachment;
import com.client360.interaction.domain.ScanStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code interaction.interaction_attachments} (SPEC.md §6.2.3, IL-US-06). */
@Repository
public class AttachmentRepository {

    private static final String COLUMNS = """
            id, interaction_id, filename, content_type, size_bytes, storage_key,
            checksum_sha256, scan::text AS scan, scanned_at, uploaded_by, uploaded_at, deleted_at
            """;

    private final JdbcClient jdbc;

    public AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts what is already attached, holding the parent row.
     *
     * <p>{@code FOR UPDATE} on the interaction, not on the attachments: the five-file limit is a
     * property of the parent (§6.2.3), and two uploads racing would each count four and each write
     * a fifth. Locking the parent serialises them. There is no constraint that could catch it —
     * a per-parent count is not expressible as a CHECK.
     */
    public int countForUpdate(UUID interactionId) {
        jdbc.sql("SELECT 1 FROM interactions WHERE id = :id FOR UPDATE")
                .param("id", interactionId)
                .query(Integer.class)
                .optional();
        return jdbc.sql("SELECT count(*) FROM interaction_attachments"
                        + " WHERE interaction_id = :id AND deleted_at IS NULL")
                .param("id", interactionId)
                .query(Integer.class)
                .single();
    }

    public Attachment insert(Attachment draft) {
        return jdbc.sql("""
                        INSERT INTO interaction_attachments
                            (id, interaction_id, filename, content_type, size_bytes, storage_key,
                             checksum_sha256, uploaded_by)
                        VALUES (:id, :interactionId, :filename, :contentType, :sizeBytes, :storageKey,
                                :checksum, :uploadedBy)
                        RETURNING
                        """ + COLUMNS)
                .param("id", draft.id())
                .param("interactionId", draft.interactionId())
                .param("filename", draft.filename())
                .param("contentType", draft.contentType())
                .param("sizeBytes", draft.sizeBytes())
                .param("storageKey", draft.storageKey())
                .param("checksum", draft.checksumSha256())
                .param("uploadedBy", draft.uploadedBy())
                .query(this::map)
                .single();
    }

    public List<Attachment> listFor(UUID interactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM interaction_attachments"
                        + " WHERE interaction_id = :id AND deleted_at IS NULL ORDER BY uploaded_at, id")
                .param("id", interactionId)
                .query(this::map)
                .list();
    }

    public Optional<Attachment> findLive(UUID interactionId, UUID attachmentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM interaction_attachments"
                        + " WHERE id = :id AND interaction_id = :interactionId AND deleted_at IS NULL")
                .param("id", attachmentId)
                .param("interactionId", interactionId)
                .query(this::map)
                .optional();
    }

    /** Soft delete, like everything else here. The object stays; the row stops being served. */
    public boolean softDelete(UUID interactionId, UUID attachmentId) {
        return jdbc.sql("UPDATE interaction_attachments SET deleted_at = now()"
                                + " WHERE id = :id AND interaction_id = :interactionId AND deleted_at IS NULL")
                        .param("id", attachmentId)
                        .param("interactionId", interactionId)
                        .update()
                == 1;
    }

    private Attachment map(ResultSet rs, int rowNum) throws SQLException {
        return new Attachment(
                rs.getObject("id", UUID.class),
                rs.getObject("interaction_id", UUID.class),
                rs.getString("filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("storage_key"),
                rs.getBytes("checksum_sha256"),
                ScanStatus.valueOf(rs.getString("scan")),
                instant(rs, "scanned_at"),
                rs.getObject("uploaded_by", UUID.class),
                instant(rs, "uploaded_at"),
                instant(rs, "deleted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
