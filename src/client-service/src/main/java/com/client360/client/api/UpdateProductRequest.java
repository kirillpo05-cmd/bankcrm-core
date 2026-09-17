package com.client360.client.api;

import com.client360.client.domain.ProductStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code PATCH /clients/{id}/products/{productId}} body (SPEC.md §5.3).
 *
 * <p>Unlike the client patch, absent and {@code null} mean the same thing here: leave it alone.
 * A product is a projection of core banking, and nothing about it is a field a user clears by
 * hand — the sync path rewrites the whole row instead. That removes the need for three-state
 * fields and keeps this an ordinary validated DTO.
 *
 * <p>{@code type}, {@code externalProductId} and {@code openedOn} are absent on purpose: they
 * identify the product in the core system, and a change there is a different product.
 */
public record UpdateProductRequest(
        @Size(max = 32) String maskedNumber,
        ProductStatus status,

        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO currency code")
        String currency,

        Long balanceMinor,
        @JsonFormat(pattern = "uuuu-MM-dd") LocalDate closedOn) {}
