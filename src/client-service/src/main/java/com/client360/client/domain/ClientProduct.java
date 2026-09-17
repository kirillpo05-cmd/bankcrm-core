package com.client360.client.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row of {@code client.client_products} (SPEC.md §5.2.3).
 *
 * <p>A read-only projection of core banking: this service never opens an account or moves a
 * balance. Everything here mirrors what the core said at {@code syncedAt}, which is why the card
 * shows the age of that sync rather than implying live data.
 *
 * <p>Money is {@code balanceMinor} in minor units plus an ISO currency, never a floating-point
 * amount (CLAUDE.md rule 6). The balance is nullable: a card that has not settled yet has no
 * meaningful one, and zero would be a lie.
 */
public record ClientProduct(
        UUID id,
        UUID clientId,
        ProductType type,
        String externalProductId,
        String maskedNumber,
        ProductStatus status,
        String currency,
        Long balanceMinor,
        LocalDate openedOn,
        LocalDate closedOn,
        Instant syncedAt,
        Instant createdAt,
        Instant updatedAt,
        int version) {

    /** S-CP-04: past a day old the card shows an amber "synced N days ago" chip. */
    public static final long STALE_AFTER_MINUTES = 1440;

    public boolean isActive() {
        return status == ProductStatus.ACTIVE;
    }

    public boolean isClosed() {
        return status == ProductStatus.CLOSED;
    }

    /** A balance that is present and not zero. Absent is not the same as settled at zero. */
    public boolean hasOutstandingBalance() {
        return balanceMinor != null && balanceMinor != 0;
    }
}
