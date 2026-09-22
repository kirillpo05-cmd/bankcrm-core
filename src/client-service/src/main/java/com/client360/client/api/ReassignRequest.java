package com.client360.client.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST /clients/{id}/reassign} body (SPEC.md §5.3, CP-US-05).
 *
 * <p>The reason is required and checked for length in the service, not here: §5.3 answers a short
 * reason with {@code 422}, which makes it a business rule rather than a malformed request. It is
 * the whole point of the endpoint — coverage changes hands during holidays and departures, and a
 * year later someone needs to know why this client moved.
 *
 * @param transferOpenTasks whether open tasks follow the client or stay with the previous owner
 */
public record ReassignRequest(
        @NotNull UUID newOwnerManagerId, @Size(max = 500) String reason, Boolean transferOpenTasks) {

    /** Tasks staying put is the safer default: work already promised keeps its owner. */
    public ReassignRequest {
        transferOpenTasks = transferOpenTasks != null && transferOpenTasks;
    }
}
