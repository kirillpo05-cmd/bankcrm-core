package com.client360.client.service;

import static com.client360.common.security.Permissions.CLIENT_READ;
import static com.client360.common.security.Permissions.PRODUCT_WRITE;

import com.client360.client.api.ClientErrorCodes;
import com.client360.client.api.CreateProductRequest;
import com.client360.client.api.ProductResponse;
import com.client360.client.api.UpdateProductRequest;
import com.client360.client.domain.Client;
import com.client360.client.domain.ClientProduct;
import com.client360.client.domain.ProductStatus;
import com.client360.client.domain.ProductType;
import com.client360.client.persistence.ClientProductRepository;
import com.client360.client.persistence.ClientProductRepository.NewProduct;
import com.client360.client.persistence.ClientRepository;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.id.UuidV7;
import com.client360.common.security.CurrentUser;
import com.client360.common.time.DatabaseClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The product sub-resource (SPEC.md §5.3, CP-US-07).
 *
 * <p>Products are a read-only projection of core banking: this service records what the core
 * reported and never opens an account or moves a balance. "Write" here means "record the feed",
 * which is why {@code product:write} belongs to an admin or the sync service account and not to
 * the manager who owns the client.
 */
@Service
public class ClientProductService {

    private final ClientRepository clients;
    private final ClientProductRepository products;
    private final ClientAccess access;
    private final ClientEvents events;
    private final DatabaseClock clock;

    public ClientProductService(
            ClientRepository clients,
            ClientProductRepository products,
            ClientAccess access,
            ClientEvents events,
            DatabaseClock clock) {
        this.clients = clients;
        this.products = products;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    /**
     * CP-US-07. No {@code READ_SENSITIVE}: nothing here is decrypted PII, and CP-BR-13 ties the
     * audit event to a genuine disclosure rather than to every panel that renders.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> list(UUID clientId, ProductStatus status, ProductType type, CurrentUser caller) {
        readableClient(clientId, caller);
        Instant now = clock.now();
        return products.findByClient(clientId, status, type).stream()
                .map(product -> ProductResponse.of(product, now))
                .toList();
    }

    /** CP-BR-07: a blocked client, or one whose KYC is rejected or expired, takes no new products. */
    @Transactional
    public ProductResponse add(UUID clientId, CreateProductRequest request, CurrentUser caller) {
        Client client = readableClient(clientId, caller);
        requireProductWrite(client, caller);
        if (client.productsReadOnly()) {
            throw ApiException.businessRule("This client is not cleared for new products while KYC is "
                            + client.kycStatus() + " and status is " + client.status() + " (CP-BR-07).")
                    .detail(
                            "kycStatus",
                            client.kycStatus().name(),
                            "status",
                            client.status().name());
        }
        requireCoherentClosure(request.status(), request.closedOn());

        // Pre-checked for a precise code; uq_products_external is what makes it true under a race,
        // and a repeated feed must update the projection rather than duplicate it (CP-EC-11).
        products.findByExternalId(clientId, request.externalProductId()).ifPresent(existing -> {
            throw ApiException.conflict(
                            ClientErrorCodes.PRODUCT_DUPLICATE_EXTERNAL_ID,
                            "This product is already recorded for the client.")
                    .detail("productId", existing.id());
        });

        ClientProduct created = products.insert(new NewProduct(
                UuidV7.next(),
                clientId,
                request.type(),
                request.externalProductId().trim(),
                request.maskedNumber(),
                request.status(),
                request.currency(),
                request.balanceMinor(),
                request.openedOn(),
                request.closedOn()));
        events.productAdded(created);
        return ProductResponse.of(created, clock.now());
    }

    /**
     * Updating the projection from a later view of core banking. Editing is deliberately not
     * blocked by CP-BR-07: that rule stops a blocked client acquiring <em>new</em> products, and
     * closing an existing one is part of how such a relationship is wound down.
     */
    @Transactional
    public ProductResponse update(
            UUID clientId, UUID productId, int expectedVersion, UpdateProductRequest request, CurrentUser caller) {
        Client client = readableClient(clientId, caller);
        requireProductWrite(client, caller);

        ClientProduct current = products.findById(clientId, productId)
                .orElseThrow(() ->
                        ApiException.notFound(ClientErrorCodes.PRODUCT_NOT_FOUND, "No such product on this client."));
        if (current.version() != expectedVersion) {
            throw versionConflict(current);
        }

        ClientProduct next = new ClientProduct(
                current.id(),
                current.clientId(),
                current.type(),
                current.externalProductId(),
                request.maskedNumber() == null ? current.maskedNumber() : request.maskedNumber(),
                request.status() == null ? current.status() : request.status(),
                request.currency() == null ? current.currency() : request.currency(),
                request.balanceMinor() == null ? current.balanceMinor() : request.balanceMinor(),
                current.openedOn(),
                request.closedOn() == null ? current.closedOn() : request.closedOn(),
                current.syncedAt(),
                current.createdAt(),
                current.updatedAt(),
                current.version());

        requireCoherentClosure(next.status(), next.closedOn());
        // §5.3: closing a product that still holds money would hide an open liability behind a
        // tidy-looking card. The core system settles it first.
        if (next.isClosed() && !current.isClosed() && next.hasOutstandingBalance()) {
            throw ApiException.businessRule("A product with a non-zero balance cannot be closed.")
                    .detail("field", "status", "issue", "balance must be settled before closing");
        }

        ClientProduct saved = products.update(next, expectedVersion)
                .orElseThrow(() -> versionConflict(products.findById(clientId, productId)
                        .orElseThrow(() -> ApiException.notFound(
                                ClientErrorCodes.PRODUCT_NOT_FOUND, "No such product on this client."))));
        events.productUpdated(current, saved);
        return ProductResponse.of(saved, clock.now());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * ER-01 first: a caller who cannot see the client must not learn about it through its
     * products. Only then is the product permission considered, so a {@code 403} never doubles as
     * proof that a record exists.
     */
    private Client readableClient(UUID clientId, CurrentUser caller) {
        Client client = clients.findById(clientId).orElseThrow(ClientAccess::notFound);
        access.scopeOf(caller, CLIENT_READ)
                .filter(scope -> access.covers(scope, client, caller))
                .orElseThrow(() -> {
                    events.recordDenial(clientId, CLIENT_READ);
                    return ClientAccess.notFound();
                });
        return client;
    }

    private void requireProductWrite(Client client, CurrentUser caller) {
        boolean allowed = access.scopeOf(caller, PRODUCT_WRITE)
                .filter(scope -> access.covers(scope, client, caller))
                .isPresent();
        if (!allowed) {
            events.recordDenial(client.id(), PRODUCT_WRITE);
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Recording products requires the " + PRODUCT_WRITE + " permission.")
                    .detail("permission", PRODUCT_WRITE);
        }
    }

    /**
     * The DDL already refuses both of these (ck_products_closed_has_date,
     * ck_products_open_has_no_close). Checking here only buys a message that names the field —
     * the database stays the line that makes it true (rule 9).
     */
    private static void requireCoherentClosure(ProductStatus status, java.time.LocalDate closedOn) {
        if (status == ProductStatus.CLOSED && closedOn == null) {
            throw ApiException.businessRule("A closed product needs a closing date.")
                    .detail("field", "closedOn", "issue", "required when status is CLOSED");
        }
        if (status != ProductStatus.CLOSED && closedOn != null) {
            throw ApiException.businessRule("Only a closed product may carry a closing date.")
                    .detail("field", "closedOn", "issue", "allowed only when status is CLOSED");
        }
    }

    private ApiException versionConflict(ClientProduct current) {
        return ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT,
                        "This product was changed by someone else. Review the current values and retry.")
                .detail(Map.of("current", ProductResponse.of(current, clock.now())));
    }
}
