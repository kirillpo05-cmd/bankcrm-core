package com.client360.client.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of {@code POST /clients/merge} (SPEC.md §5.3, CP-US-06).
 *
 * @param moved what this service re-pointed itself, in the same transaction as the merge
 * @param skipped products the survivor already had under the same {@code externalProductId}. The
 *     merge succeeds without them rather than failing: a duplicate record of a closed account is
 *     exactly the sort of debris a merge exists to clear up (CP-BR-10)
 * @param pending resources another service owns and will re-point when it sees {@code
 *     client.merged}. Named rather than counted, because a count this service does not have would
 *     be a number it made up — interactions live in interaction-service, and no transaction spans
 *     both. Until it catches up, the loser's timeline is reachable through the {@code 410}'s
 *     {@code Location}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MergeResponse(
        ClientResponse survivor, Moved moved, Skipped skipped, List<String> pending, UUID mergedId) {

    public record Moved(int products) {}

    public record Skipped(List<String> products) {}
}
