package com.client360.client.persistence;

import com.client360.client.domain.Client;
import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.ContactChannel;
import com.client360.client.domain.KycStatus;
import com.client360.client.domain.RiskRating;
import com.client360.common.crypto.FieldCipher;
import com.client360.common.crypto.LookupHasher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code client.clients} access. The only layer that sees ciphertext: it encrypts on the way in
 * and decrypts on the way out, so the service layer deals in plaintext and the database never
 * holds any (SPEC.md §4.7, CLAUDE.md rule 8).
 *
 * <p>Every lookup by a sensitive value goes through {@link LookupHasher} against the
 * {@code _hash} sibling column. There is deliberately no prefix, substring or {@code LIKE} search
 * on those columns — only the plaintext name columns are searchable, by trigram.
 *
 * <p>Queries are literal SQL with named parameters throughout. Nothing here concatenates a
 * caller-supplied value into a statement.
 */
@Repository
public class ClientRepository {

    /**
     * Authenticated but not stored (see {@link FieldCipher}): ciphertext moved from one column to
     * another fails to decrypt instead of surfacing under the wrong label.
     */
    static final String AAD_EMAIL = "client.clients.email";

    static final String AAD_PHONE = "client.clients.phone";
    static final String AAD_TAX_ID = "client.clients.tax_id";
    static final String AAD_ADDRESS = "client.clients.address";

    private static final String COLUMNS = """
            id, external_ref, first_name, last_name, middle_name, date_of_birth,
            email_enc, phone_enc, tax_id_enc, address_enc, key_version,
            preferred_channel::text AS preferred_channel,
            segment::text           AS segment,
            status::text            AS status,
            risk::text              AS risk,
            kyc_status::text        AS kyc_status,
            kyc_verified_at, kyc_expires_at, kyc_rejection_reason,
            owner_manager_id, team_id, last_interaction_at, open_task_count,
            merged_into_id, merged_at, version,
            created_at, created_by, updated_at, updated_by, deleted_at
            """;

    /** CP-EC-09: merge chains are legal; the hop cap is what stops a cycle from looping forever. */
    private static final int MAX_MERGE_HOPS = 10;

    private final JdbcClient jdbc;
    private final FieldCipher cipher;
    private final LookupHasher hasher;

    public ClientRepository(JdbcClient jdbc, FieldCipher cipher, LookupHasher hasher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.hasher = hasher;
    }

    // ------------------------------------------------------------------- writes

    /**
     * Inserts and returns the stored row. {@code status}, {@code risk} and {@code kyc_status} are
     * left to their column defaults (ACTIVE / LOW / NOT_STARTED): a create cannot set them, so
     * naming them here would only invite drift from the DDL.
     */
    public Client insert(NewClient draft) {
        return jdbc.sql("""
                        INSERT INTO clients (
                            id, external_ref, first_name, last_name, middle_name, date_of_birth,
                            email_enc, email_hash, phone_enc, phone_hash,
                            tax_id_enc, tax_id_hash, address_enc, key_version,
                            preferred_channel, segment, owner_manager_id, team_id,
                            created_by, updated_by)
                        VALUES (
                            :id, :externalRef, :firstName, :lastName, :middleName, :dateOfBirth,
                            :emailEnc, :emailHash, :phoneEnc, :phoneHash,
                            :taxIdEnc, :taxIdHash, :addressEnc, :keyVersion,
                            CAST(:preferredChannel AS contact_channel),
                            CAST(:segment AS client_segment),
                            :ownerManagerId, :teamId, :actor, :actor)
                        RETURNING
                        """ + COLUMNS)
                .param("id", draft.id())
                .param("externalRef", draft.externalRef())
                .param("firstName", draft.firstName())
                .param("lastName", draft.lastName())
                .param("middleName", draft.middleName())
                .param("dateOfBirth", draft.dateOfBirth())
                .param("emailEnc", cipher.encrypt(draft.email(), AAD_EMAIL))
                .param("emailHash", hasher.hash(draft.email()))
                .param("phoneEnc", cipher.encrypt(draft.phone(), AAD_PHONE))
                .param("phoneHash", hasher.hash(draft.phone()))
                .param("taxIdEnc", cipher.encrypt(draft.taxId(), AAD_TAX_ID))
                .param("taxIdHash", hasher.hash(draft.taxId()))
                .param("addressEnc", cipher.encrypt(draft.address(), AAD_ADDRESS))
                .param("keyVersion", cipher.currentKeyVersion())
                .param("preferredChannel", draft.preferredChannel().name())
                .param("segment", draft.segment().name())
                .param("ownerManagerId", draft.ownerManagerId())
                .param("teamId", draft.teamId())
                .param("actor", draft.actorId())
                .query(this::map)
                .single();
    }

    // -------------------------------------------------------------------- reads

    /** The card read: soft-deleted rows are invisible, which is what makes ER-01's 404 truthful. */
    public Optional<Client> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /**
     * Including soft-deleted rows. Only for resolving a merged record to its survivor (CP-BR-10):
     * a deleted row must never reach a card response.
     */
    public Optional<Client> findAnyById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<Client> findByExternalRef(String externalRef) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE external_ref = :ref AND deleted_at IS NULL")
                .param("ref", externalRef)
                .query(this::map)
                .optional();
    }

    /** Email is unique among non-deleted clients (CP-BR-02), so at most one row can match. */
    public Optional<Client> findByEmail(String normalizedEmail) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE email_hash = :hash AND deleted_at IS NULL")
                .param("hash", hasher.hash(normalizedEmail))
                .query(this::map)
                .optional();
    }

    public Optional<Client> findByTaxId(String taxId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM clients WHERE tax_id_hash = :hash AND deleted_at IS NULL")
                .param("hash", hasher.hash(taxId))
                .query(this::map)
                .optional();
    }

    /**
     * Phone is deliberately NOT unique (CP-BR-02, CP-EC-03): a mother and son share a landline, and
     * the lookup returns both for disambiguation rather than rejecting the second record.
     */
    public List<Client> findAllByPhone(String e164Phone, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM clients
                         WHERE phone_hash = :hash AND deleted_at IS NULL
                         ORDER BY last_name, first_name, id
                         LIMIT :limit
                        """)
                .param("hash", hasher.hash(e164Phone))
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * Trigram name search, served by {@code ix_clients_name_trgm}. The similarity operator is
     * bounded by {@code pg_trgm.similarity_threshold}, so a noisy query returns few rows rather
     * than the whole table.
     */
    public List<Client> searchByName(String query, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM clients
                         WHERE deleted_at IS NULL
                           AND lower(first_name || ' ' || last_name) % lower(:q)
                         ORDER BY similarity(lower(first_name || ' ' || last_name), lower(:q)) DESC,
                                  last_name, first_name, id
                         LIMIT :limit
                        """)
                .param("q", query)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    /**
     * Follows {@code merged_into_id} to the end of the chain (CP-EC-09). Returns empty when the
     * chain exceeds {@link #MAX_MERGE_HOPS}, which can only mean a cycle — the caller turns that
     * into a 500 with an alert rather than looping.
     */
    public Optional<Client> resolveMergeSurvivor(Client merged) {
        Client current = merged;
        for (int hop = 0; hop < MAX_MERGE_HOPS; hop++) {
            UUID next = current.mergedIntoId();
            if (next == null) {
                return Optional.of(current);
            }
            Optional<Client> found = findAnyById(next);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            current = found.get();
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ mapping

    private Client map(ResultSet rs, int rowNum) throws SQLException {
        short keyVersion = rs.getShort("key_version");
        return new Client(
                rs.getObject("id", UUID.class),
                rs.getString("external_ref"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("middle_name"),
                rs.getObject("date_of_birth", LocalDate.class),
                cipher.decrypt(rs.getBytes("email_enc"), keyVersion, AAD_EMAIL),
                cipher.decrypt(rs.getBytes("phone_enc"), keyVersion, AAD_PHONE),
                cipher.decrypt(rs.getBytes("tax_id_enc"), keyVersion, AAD_TAX_ID),
                cipher.decrypt(rs.getBytes("address_enc"), keyVersion, AAD_ADDRESS),
                ContactChannel.valueOf(rs.getString("preferred_channel")),
                ClientSegment.valueOf(rs.getString("segment")),
                ClientStatus.valueOf(rs.getString("status")),
                RiskRating.valueOf(rs.getString("risk")),
                KycStatus.valueOf(rs.getString("kyc_status")),
                instant(rs, "kyc_verified_at"),
                instant(rs, "kyc_expires_at"),
                rs.getString("kyc_rejection_reason"),
                rs.getObject("owner_manager_id", UUID.class),
                rs.getObject("team_id", UUID.class),
                instant(rs, "last_interaction_at"),
                rs.getInt("open_task_count"),
                rs.getObject("merged_into_id", UUID.class),
                instant(rs, "merged_at"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class),
                instant(rs, "updated_at"),
                rs.getObject("updated_by", UUID.class),
                instant(rs, "deleted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * A validated create, with identifiers already normalized. Normalization happens above this
     * layer so that one function serves both the write and the lookup path.
     */
    public record NewClient(
            UUID id,
            String externalRef,
            String firstName,
            String lastName,
            String middleName,
            LocalDate dateOfBirth,
            String email,
            String phone,
            String taxId,
            String address,
            ContactChannel preferredChannel,
            ClientSegment segment,
            UUID ownerManagerId,
            UUID teamId,
            UUID actorId) {}
}
