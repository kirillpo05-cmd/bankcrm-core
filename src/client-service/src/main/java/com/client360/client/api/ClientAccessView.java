package com.client360.client.api;

import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.KycStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * The answer to "may this caller do {@code permission} on this client, and what state is the
 * client in?" — the internal contract other services authorize against (SPEC.md §5.3, §3.1).
 *
 * <p>Deliberately holds no PII. A service that needs to know whether it may write an interaction
 * does not need the client's name, email or phone, and fetching the full card for an
 * authorization check would disclose all three between services and audit a {@code
 * READ_SENSITIVE} for a decision nobody read (CP-BR-13).
 *
 * <p>What is here is exactly what a caller must know to apply the client's own rules: CP-BR-08
 * closes a client to everything but notes, and CP-BR-07 turns on {@code kycStatus} and
 * {@code status}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClientAccessView(
        UUID clientId, UUID ownerManagerId, UUID teamId, ClientStatus status, KycStatus kycStatus) {}
