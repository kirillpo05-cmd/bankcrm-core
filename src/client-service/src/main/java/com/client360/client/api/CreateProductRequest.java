package com.client360.client.api;

import com.client360.client.domain.ProductStatus;
import com.client360.client.domain.ProductType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /clients/{id}/products} body (SPEC.md §5.3).
 *
 * <p>This endpoint records what core banking reported; it does not open an account. The caller is
 * the sync service account or an admin, and {@code externalProductId} is the core's identifier —
 * the key the projection is kept in step by, and what makes a repeated feed idempotent rather
 * than duplicating the product (CP-EC-11).
 */
public record CreateProductRequest(
        @NotNull ProductType type,
        @NotBlank @Size(max = 64) String externalProductId,
        @Size(max = 32) String maskedNumber,
        @NotNull ProductStatus status,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter ISO currency code")
        String currency,

        Long balanceMinor,
        @NotNull @PastOrPresent LocalDate openedOn,
        @JsonFormat(pattern = "uuuu-MM-dd") LocalDate closedOn) {}
