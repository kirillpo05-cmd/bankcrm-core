package com.client360.client.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * A staff member as another service sees them ({@code GET /internal/users}, SPEC.md §5.3).
 *
 * <p>Its own type rather than reusing the {@code owner} block of the client card: the card shows a
 * name, and this answers "who is this, and whose team are they on" — a question interaction-service
 * asks to scope a supervisor's feed (IL-US-07). Widening the card's block to carry it would have
 * put org structure on every client read that never needed it.
 *
 * <p>Corporate directory data, not client PII: {@code full_name} on {@code users} is deliberately
 * plaintext (§4.7), so nothing here is masked.
 *
 * @param teamId the user's primary team, {@code null} for the cross-team roles that have none
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InternalUserView(UUID id, String fullName, UUID teamId) {}
