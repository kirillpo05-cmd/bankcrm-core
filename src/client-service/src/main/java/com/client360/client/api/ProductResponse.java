package com.client360.client.api;

import com.client360.client.domain.ClientProduct;
import com.client360.client.domain.ProductStatus;
import com.client360.client.domain.ProductType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A product on the client card (SPEC.md §5.3, CP-US-07).
 *
 * <p>{@code syncAgeMinutes} and {@code stale} are served rather than left to the client to work
 * out, because the card must be honest about how old this is: the data comes from core banking as
 * of {@code syncedAt}, and a panel that looks live when it is two days old invites a manager to
 * quote a stale balance to the customer (S-CP-04).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProductResponse(
        UUID id,
        ProductType type,
        String maskedNumber,
        ProductStatus status,
        Money balance,
        LocalDate openedOn,
        LocalDate closedOn,
        Instant syncedAt,
        Long syncAgeMinutes,
        boolean stale) {

    /** Minor units plus ISO currency. No floating point anywhere near a balance (rule 6). */
    public record Money(long amountMinor, String currency) {}

    public static ProductResponse of(ClientProduct product, Instant now) {
        Long ageMinutes = product.syncedAt() == null
                ? null
                : Math.max(0, Duration.between(product.syncedAt(), now).toMinutes());
        return new ProductResponse(
                product.id(),
                product.type(),
                product.maskedNumber(),
                product.status(),
                // Absent is not zero: a card that has not settled has no meaningful balance.
                product.balanceMinor() == null ? null : new Money(product.balanceMinor(), product.currency()),
                product.openedOn(),
                product.closedOn(),
                product.syncedAt(),
                ageMinutes,
                ageMinutes != null && ageMinutes > ClientProduct.STALE_AFTER_MINUTES);
    }
}
