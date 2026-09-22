package com.client360.client.api;

import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.KycStatus;
import java.util.UUID;

/**
 * The query string of {@code GET /clients} (SPEC.md §5.3), carried as one value rather than a
 * dozen parameters threaded through the service.
 *
 * <p>{@code ownerId} and {@code teamId} narrow a search inside what the caller may already see.
 * They are not the caller's scope and can never widen it — the service resolves that separately
 * and applies both.
 *
 * <p>Nothing is validated here: page bounds, the sort whitelist and the expiry window all produce
 * {@code 400 VALIDATION_FAILED} with a field name, which is the service's job because it is the
 * layer that knows what a legal sort field is.
 */
public record ClientListQuery(
        String q,
        ClientSegment segment,
        ClientStatus status,
        KycStatus kycStatus,
        UUID ownerId,
        UUID teamId,
        Integer kycExpiringWithinDays,
        Integer page,
        Integer size,
        String sort) {}
