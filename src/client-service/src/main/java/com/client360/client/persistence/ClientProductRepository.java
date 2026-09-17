package com.client360.client.persistence;

import com.client360.client.domain.ClientProduct;
import com.client360.client.domain.ProductStatus;
import com.client360.client.domain.ProductType;
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
 * {@code client.client_products} access (SPEC.md §5.2.3).
 *
 * <p>Nothing here is encrypted: a masked number and a balance are not PII on their own, and the
 * card would be useless without them. The link back to the person is {@code client_id}, which is
 * what an erasure leaves behind (CP-EC-12).
 *
 * <p>The filters on the list query are bound as parameters, not concatenated — a null filter is
 * expressed in SQL rather than by building a different statement, so there is exactly one query
 * plan and no string assembled from what the caller sent.
 */
@Repository
public class ClientProductRepository {

    private static final String COLUMNS = """
            id, client_id, product_type::text AS product_type, external_product_id, masked_number,
            status::text AS status, currency, balance_minor, opened_on, closed_on,
            synced_at, created_at, updated_at, version
            """;

    private final JdbcClient jdbc;

    public ClientProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** CP-US-07: the products panel, optionally narrowed by status or type. */
    public List<ClientProduct> findByClient(UUID clientId, ProductStatus status, ProductType type) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM client_products
                         WHERE client_id = :clientId
                           AND (CAST(:status AS text) IS NULL OR status::text = CAST(:status AS text))
                           AND (CAST(:type AS text) IS NULL OR product_type::text = CAST(:type AS text))
                         ORDER BY product_type, opened_on DESC, id
                        """)
                .param("clientId", clientId)
                .param("status", status == null ? null : status.name())
                .param("type", type == null ? null : type.name())
                .query(this::map)
                .list();
    }

    /** Scoped by client on purpose: a product id from another client must not resolve here. */
    public Optional<ClientProduct> findById(UUID clientId, UUID productId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM client_products WHERE client_id = :clientId AND id = :id")
                .param("clientId", clientId)
                .param("id", productId)
                .query(this::map)
                .optional();
    }

    public ClientProduct insert(NewProduct draft) {
        return jdbc.sql("""
                        INSERT INTO client_products (
                            id, client_id, product_type, external_product_id, masked_number,
                            status, currency, balance_minor, opened_on, closed_on, synced_at)
                        VALUES (
                            :id, :clientId,
                            CAST(:type AS product_type), :externalProductId, :maskedNumber,
                            CAST(:status AS product_status), :currency, :balanceMinor,
                            :openedOn, :closedOn, now())
                        RETURNING
                        """ + COLUMNS)
                .param("id", draft.id())
                .param("clientId", draft.clientId())
                .param("type", draft.type().name())
                .param("externalProductId", draft.externalProductId())
                .param("maskedNumber", draft.maskedNumber())
                .param("status", draft.status().name())
                .param("currency", draft.currency())
                .param("balanceMinor", draft.balanceMinor())
                .param("openedOn", draft.openedOn())
                .param("closedOn", draft.closedOn())
                .query(this::map)
                .single();
    }

    /**
     * Writes the whole mutable state if the row is still at {@code expectedVersion}. Empty means
     * someone else wrote first, and the caller answers {@code 409 VERSION_CONFLICT} (§4.8).
     *
     * <p>{@code synced_at} moves with every write: the value now reflects what the writer knew,
     * and the card's freshness chip should say so.
     */
    public Optional<ClientProduct> update(ClientProduct next, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE client_products SET
                            masked_number = :maskedNumber,
                            status        = CAST(:status AS product_status),
                            currency      = :currency,
                            balance_minor = :balanceMinor,
                            closed_on     = :closedOn,
                            synced_at     = now(),
                            updated_at    = now(),
                            version       = version + 1
                        WHERE id = :id AND client_id = :clientId AND version = :expectedVersion
                        RETURNING
                        """ + COLUMNS)
                .param("id", next.id())
                .param("clientId", next.clientId())
                .param("expectedVersion", expectedVersion)
                .param("maskedNumber", next.maskedNumber())
                .param("status", next.status().name())
                .param("currency", next.currency())
                .param("balanceMinor", next.balanceMinor())
                .param("closedOn", next.closedOn())
                .query(this::map)
                .optional();
    }

    public Optional<ClientProduct> findByExternalId(UUID clientId, String externalProductId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM client_products WHERE client_id = :clientId AND external_product_id = :ref")
                .param("clientId", clientId)
                .param("ref", externalProductId)
                .query(this::map)
                .optional();
    }

    /** CP-BR-09: a client holding a live banking relationship cannot be soft-deleted. */
    public boolean hasActiveProduct(UUID clientId) {
        Integer count = jdbc.sql(
                        "SELECT count(*) FROM client_products WHERE client_id = :clientId AND status = 'ACTIVE'")
                .param("clientId", clientId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private ClientProduct map(ResultSet rs, int rowNum) throws SQLException {
        Long balance = rs.getObject("balance_minor", Long.class);
        return new ClientProduct(
                rs.getObject("id", UUID.class),
                rs.getObject("client_id", UUID.class),
                ProductType.valueOf(rs.getString("product_type")),
                rs.getString("external_product_id"),
                rs.getString("masked_number"),
                ProductStatus.valueOf(rs.getString("status")),
                rs.getString("currency"),
                balance,
                rs.getObject("opened_on", LocalDate.class),
                rs.getObject("closed_on", LocalDate.class),
                instant(rs, "synced_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getInt("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** A validated product as core banking reported it. */
    public record NewProduct(
            UUID id,
            UUID clientId,
            ProductType type,
            String externalProductId,
            String maskedNumber,
            ProductStatus status,
            String currency,
            Long balanceMinor,
            LocalDate openedOn,
            LocalDate closedOn) {}
}
