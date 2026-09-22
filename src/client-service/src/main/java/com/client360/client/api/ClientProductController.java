package com.client360.client.api;

import com.client360.client.domain.ProductStatus;
import com.client360.client.domain.ProductType;
import com.client360.client.service.ClientProductService;
import com.client360.common.idempotency.IdempotencyService;
import com.client360.common.security.CurrentUser;
import com.client360.common.web.ETags;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product sub-resource (SPEC.md §5.3).
 *
 * <p>{@code POST /clients/{id}/products/sync} is not here yet: it pulls from core banking, and
 * there is no adapter to pull from. A stub that always answered {@code 503} would look like an
 * outage rather than an unbuilt feature.
 */
@RestController
@RequestMapping("/api/v1/clients/{clientId}/products")
public class ClientProductController {

    private final ClientProductService products;
    private final IdempotencyService idempotency;

    public ClientProductController(ClientProductService products, IdempotencyService idempotency) {
        this.products = products;
        this.idempotency = idempotency;
    }

    /** {@code 200} with an empty array when the client holds none — never {@code 404} (§5.3). */
    @GetMapping
    public List<ProductResponse> list(
            @PathVariable UUID clientId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) ProductType type,
            CurrentUser caller) {
        return products.list(clientId, status, type, caller);
    }

    @PostMapping
    public ResponseEntity<Object> add(
            @PathVariable UUID clientId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateProductRequest request,
            CurrentUser caller) {
        return idempotency.execute(idempotencyKey, request, () -> {
            ProductResponse created = products.add(clientId, request, caller);
            return ResponseEntity.created(URI.create("/api/v1/clients/" + clientId + "/products/" + created.id()))
                    .body(created);
        });
    }

    @PatchMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable UUID clientId,
            @PathVariable UUID productId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateProductRequest request,
            CurrentUser caller) {
        int expectedVersion = ETags.requireIfMatch(ifMatch);
        return ResponseEntity.ok(products.update(clientId, productId, expectedVersion, request, caller));
    }
}
