package com.client360.interaction.service;

import static com.client360.common.security.Permissions.INTERACTION_DELETE;
import static com.client360.common.security.Permissions.INTERACTION_READ;
import static com.client360.common.security.Permissions.INTERACTION_WRITE;

import com.client360.common.api.ApiException;
import com.client360.common.id.UuidV7;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.Cursor;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.api.CreateInteractionRequest;
import com.client360.interaction.api.InteractionErrorCodes;
import com.client360.interaction.api.InteractionResponse;
import com.client360.interaction.api.TimelineEntry;
import com.client360.interaction.api.UserSummary;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.client.ClientAccessView;
import com.client360.interaction.client.UserDirectoryClient;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.persistence.InteractionRepository;
import com.client360.interaction.persistence.InteractionRepository.NewInteraction;
import com.client360.interaction.persistence.InteractionRepository.TimelineQuery;
import com.client360.interaction.support.PanMasker;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Interaction Log (SPEC.md §6). */
@Service
public class InteractionService {

    /** §4.5: a feed page, not a report. */
    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 100;

    private final InteractionRepository interactions;
    private final ClientAccessClient clientAccess;
    private final UserDirectoryClient directory;
    private final InteractionEvents events;
    private final AccessPolicy accessPolicy;
    private final DatabaseClock clock;

    public InteractionService(
            InteractionRepository interactions,
            ClientAccessClient clientAccess,
            UserDirectoryClient directory,
            InteractionEvents events,
            AccessPolicy accessPolicy,
            DatabaseClock clock) {
        this.interactions = interactions;
        this.clientAccess = clientAccess;
        this.directory = directory;
        this.events = events;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /**
     * IL-US-02. Joins the transaction {@code IdempotencyService} opened, so the interaction, its
     * outbox event and the stored idempotent response commit together (rule 3).
     */
    @Transactional
    public InteractionResponse create(UUID clientId, CreateInteractionRequest request, CurrentUser caller) {
        // client-service decides. This service owns no client table and never guesses (§3.1).
        ClientAccessView client = clientAccess.require(clientId, INTERACTION_WRITE);
        validate(request, client);

        // IL-EC-05: mask before encrypting. Encrypted-but-present still means the card number is
        // in the database, recoverable by anyone holding a row and the key.
        PanMasker.Result masked = PanMasker.mask(request.body());

        Interaction created = interactions.insert(new NewInteraction(
                UuidV7.next(),
                clientId,
                request.type(),
                request.direction(),
                request.subject().trim(),
                masked.body(),
                request.occurredAt(),
                request.durationSeconds(),
                request.outcome(),
                request.visibility(),
                InteractionSource.WEB,
                null,
                caller.id(),
                null));

        events.created(created);
        return InteractionResponse.of(
                created, authorOf(created.authorId(), caller), masked.maskedAnything() ? masked.maskedCount() : null);
    }

    /**
     * IL-US-01, the timeline. Keyset-paginated: an offset drifts under concurrent writes, and a
     * feed is the one place where that is guaranteed to happen (§4.5).
     *
     * <p>Emits nothing. A preview is not a disclosure (IL-BR-11).
     */
    @Transactional(readOnly = true)
    public KeysetPage<TimelineEntry> timeline(
            UUID clientId, InteractionType type, String cursor, Integer limit, CurrentUser caller) {
        clientAccess.require(clientId, INTERACTION_READ);
        int pageSize = requireLimit(limit);

        List<Interaction> rows = interactions.timeline(
                new TimelineQuery(clientId, type, Cursor.decode(cursor), caller.id(), isAdmin(caller)),
                // One extra row answers "is there more" without a second count over a growing feed.
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<Interaction> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<UUID, UserSummary> authors =
                directory.byIds(page.stream().map(Interaction::authorId).toList());

        List<TimelineEntry> content = page.stream()
                .map(row -> TimelineEntry.of(
                        row, authors.getOrDefault(row.authorId(), new UserSummary(row.authorId(), null))))
                .toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? new Cursor(page.getLast().occurredAt(), page.getLast().id()).encode()
                : null;
        return new KeysetPage<>(content, nextCursor, hasMore);
    }

    /** IL-BR-11: the full body is a disclosure, and this is where it gets audited. */
    @Transactional
    public InteractionResponse read(UUID id, CurrentUser caller) {
        Interaction interaction = interactions.findById(id).orElseThrow(InteractionService::notFound);
        clientAccess.require(interaction.clientId(), INTERACTION_READ);

        // IL-BR-09: a private note is the author's and an admin's. A supervisor who can see the
        // client still cannot see it — and learns nothing about whether it exists.
        if (!interaction.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(interaction.clientId(), id, INTERACTION_READ);
            throw notFound();
        }
        events.readSensitive(interaction, "interaction.body");
        return InteractionResponse.of(interaction, directory.byId(interaction.authorId()), null);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The directory is the source of truth for a display name, so create and the timeline cannot
     * disagree about what the same author is called. The token's {@code name} claim is the fallback
     * and only for the caller's own row: it came from client-service at login, so it beats showing
     * a bare id when the directory is unreachable.
     */
    private UserSummary authorOf(UUID authorId, CurrentUser caller) {
        UserSummary resolved = directory.byId(authorId);
        if (resolved.fullName() == null && authorId.equals(caller.id())) {
            return new UserSummary(authorId, caller.fullName());
        }
        return resolved;
    }

    private void validate(CreateInteractionRequest request, ClientAccessView client) {
        // CP-BR-08: a closed client accepts no new interaction except a note — the manager may
        // still need to record why it is closed.
        if (client.isClosed() && request.type() != InteractionType.NOTE) {
            throw ApiException.businessRule("A closed client accepts only NOTE interactions (CP-BR-08).")
                    .detail("field", "type", "issue", "client is CLOSED");
        }
        if (request.type() == InteractionType.TICKET) {
            // Honest refusal rather than a half-written ticket: sla_due_at is derived from priority
            // at creation and frozen (IL-BR-07), and that belongs with the ticket endpoints.
            throw ApiException.businessRule("Tickets are created through the ticket endpoints of §6.3.")
                    .detail("field", "type", "issue", "TICKET is not yet supported here");
        }
        if (request.durationSeconds() != null && !request.type().allowsDuration()) {
            throw ApiException.validation("durationSeconds", "only a CALL or a MEETING has a duration");
        }
        if (request.direction() == null) {
            throw ApiException.validation("direction", "required for this interaction type");
        }
        if (request.type().isInternalOnly()
                && request.direction() != com.client360.interaction.domain.InteractionDirection.INTERNAL) {
            throw ApiException.validation("direction", "a " + request.type() + " is always INTERNAL");
        }
        if (request.occurredAt().isAfter(clock.now().plusSeconds(300))) {
            // The trigger refuses this too (IL-EC-02); checking here buys a message that names the
            // field instead of a constraint.
            throw ApiException.businessRule("occurredAt may be at most 5 minutes ahead of server time.")
                    .detail("field", "occurredAt", "issue", "too far in the future");
        }
    }

    /**
     * IL-BR-09's "admin". Asked as permission + scope, never as a role string: {@code
     * interaction:delete} at {@code ALL} is the admin column of §9.2.8, while a supervisor holds it
     * only at {@code TEAM}.
     */
    private boolean isAdmin(CurrentUser caller) {
        return accessPolicy.holdsAtScopeAll(caller, INTERACTION_DELETE);
    }

    private static int requireLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit", "must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static ApiException notFound() {
        return ApiException.notFound(
                InteractionErrorCodes.INTERACTION_NOT_FOUND, "Interaction not found or not visible to you.");
    }
}
