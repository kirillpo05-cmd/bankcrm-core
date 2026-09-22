package com.client360.interaction.service;

import static com.client360.common.security.Permissions.INTERACTION_DELETE;
import static com.client360.common.security.Permissions.INTERACTION_READ;
import static com.client360.common.security.Permissions.INTERACTION_WRITE;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.id.UuidV7;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.Cursor;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.api.CreateCorrectionRequest;
import com.client360.interaction.api.CreateInteractionRequest;
import com.client360.interaction.api.InteractionEdit;
import com.client360.interaction.api.InteractionErrorCodes;
import com.client360.interaction.api.InteractionResponse;
import com.client360.interaction.api.InteractionResponse.CorrectionSummary;
import com.client360.interaction.api.TimelineEntry;
import com.client360.interaction.api.UserSummary;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.client.ClientAccessView;
import com.client360.interaction.client.UserDirectoryClient;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.persistence.InteractionRepository;
import com.client360.interaction.persistence.InteractionRepository.NewInteraction;
import com.client360.interaction.persistence.InteractionRepository.TimelineQuery;
import com.client360.interaction.persistence.InteractionRepository.TimelineRow;
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
        // A subject-only note ("left a voicemail") is legitimate. body_enc is NOT NULL because
        // the column must hold ciphertext, not because every note has text to say.
        PanMasker.Result masked = PanMasker.mask(request.body() == null ? "" : request.body());

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
                created,
                true,
                authorOf(created.authorId(), caller),
                List.of(),
                masked.maskedAnything() ? masked.maskedCount() : null);
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

        List<TimelineRow> rows = interactions.timeline(
                new TimelineQuery(clientId, type, Cursor.decode(cursor), caller.id(), isAdmin(caller)),
                // One extra row answers "is there more" without a second count over a growing feed.
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<TimelineRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<UUID, UserSummary> authors = directory.byIds(
                page.stream().map(row -> row.interaction().authorId()).toList());

        List<TimelineEntry> content = page.stream()
                .map(row ->
                        TimelineEntry.of(row, author(authors, row.interaction().authorId()), caller.id()))
                .toList();
        Interaction last = page.isEmpty() ? null : page.getLast().interaction();
        String nextCursor = hasMore && last != null ? new Cursor(last.occurredAt(), last.id()).encode() : null;
        return new KeysetPage<>(content, nextCursor, hasMore);
    }

    /**
     * IL-BR-11: the full body is a disclosure, and this is where it gets audited.
     *
     * <p>IL-BR-09 splits a private note's audience in two. A supervisor who can see the client
     * still gets {@code 404} and learns nothing about the note. An admin gets its metadata and a
     * {@code null} body: they may know it exists, never what it says (§12 Q-07). Their look is
     * still audited — "who looked at private notes" is exactly the question the trail must answer.
     */
    @Transactional
    public InteractionResponse read(UUID id, CurrentUser caller) {
        Interaction interaction = interactions.findById(id).orElseThrow(InteractionService::notFound);
        clientAccess.require(interaction.clientId(), INTERACTION_READ);

        if (!interaction.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(interaction.clientId(), id, INTERACTION_READ);
            throw notFound();
        }
        boolean withBody = interaction.canReadBodyAs(caller.id());
        events.readSensitive(interaction, withBody ? "interaction.body" : "interaction.private_metadata");
        return InteractionResponse.of(
                interaction, withBody, directory.byId(interaction.authorId()), correctionsOf(interaction), null);
    }

    // --------------------------------------------------------------- amendments

    /**
     * IL-US-04: the author fixes a typo inside the window. IL-BR-01 keeps the substance fixed, so
     * only the wording moves.
     *
     * <p>Checks run most fundamental first: existence and scope ({@code 404}), authorship
     * ({@code 403}), a changed substance field ({@code 422}), the window ({@code 422}), the version
     * ({@code 409}). A closed window outranks a stale version — nothing the caller retries will
     * succeed, so telling them to reload first would only waste a round trip.
     */
    @Transactional
    public InteractionResponse update(UUID id, int expectedVersion, InteractionEdit edit, CurrentUser caller) {
        Interaction current = interactions.findById(id).orElseThrow(InteractionService::notFound);
        clientAccess.require(current.clientId(), INTERACTION_WRITE);
        if (!current.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(current.clientId(), id, INTERACTION_WRITE);
            throw notFound();
        }
        // §6.3: supervisors and admins cannot silently rewrite another person's record. The
        // remedy that stays honest is a correction, which is visibly theirs.
        if (!current.authorId().equals(caller.id())) {
            events.recordDenial(current.clientId(), id, INTERACTION_WRITE);
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Only the author may edit an interaction. Record a correction instead.")
                    .detail("correctionEndpoint", correctionEndpoint(id));
        }
        if (!edit.immutableFields().isEmpty()) {
            ApiException error = ApiException.businessRule(
                    "An interaction's substance never changes; only subject and body may be edited (IL-BR-01).");
            edit.immutableFields().forEach(field -> error.detail("field", field, "issue", "immutable"));
            throw error;
        }
        if (!current.isEditableBy(caller.id(), clock.now())) {
            throw windowClosed(id);
        }
        if (current.version() != expectedVersion) {
            throw versionConflict(current, caller);
        }

        String subject = edit.subject() == null ? current.subject() : edit.subject();
        PanMasker.Result masked = PanMasker.mask(edit.body() == null ? current.body() : edit.body());
        // Nothing changed, so nothing is written: no edit counted against IL-BR-04's cap, no event.
        if (subject.equals(current.subject()) && masked.body().equals(current.body())) {
            return InteractionResponse.of(
                    current, true, authorOf(current.authorId(), caller), correctionsOf(current), null);
        }

        Interaction saved = interactions
                .updateContent(id, subject, masked.body(), expectedVersion, caller.id())
                .orElseThrow(() -> {
                    // The row moved between the read and the write. Re-read to name the reason:
                    // the window can close in the gap, and so can a concurrent edit land.
                    Interaction now = interactions.findById(id).orElseThrow(InteractionService::notFound);
                    return now.isEditableBy(caller.id(), clock.now()) ? versionConflict(now, caller) : windowClosed(id);
                });
        events.updated(current, saved);
        return InteractionResponse.of(
                saved,
                true,
                authorOf(saved.authorId(), caller),
                correctionsOf(saved),
                masked.maskedAnything() ? masked.maskedCount() : null);
    }

    /**
     * IL-BR-03: after the window the record is amended, never rewritten. The correction is a new
     * row; the original is untouched and the feed shows the pair together.
     *
     * <p>What the correction inherits from the original, and what it does not:
     *
     * <ul>
     *   <li>{@code occurredAt} — it describes the same moment, and the keyset timeline orders by it,
     *       so inheriting it is what places the pair side by side.
     *   <li>{@code visibility} — correcting a private note must not publish the correction.
     *   <li>It is a {@code NOTE}, {@code INTERNAL}, with no duration or outcome. A correction of a
     *       call is not a second call: inheriting the type would count it twice in every report of
     *       calls made, and CP-BR-08 would refuse to let anyone fix the record of a closed client.
     * </ul>
     */
    @Transactional
    public InteractionResponse correct(UUID originalId, CreateCorrectionRequest request, CurrentUser caller) {
        Interaction original = interactions.findById(originalId).orElseThrow(InteractionService::notFound);
        clientAccess.require(original.clientId(), INTERACTION_WRITE);
        if (!original.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(original.clientId(), originalId, INTERACTION_WRITE);
            throw notFound();
        }
        // Amending what you are not allowed to read would be writing blind.
        if (!original.canReadBodyAs(caller.id())) {
            events.recordDenial(original.clientId(), originalId, INTERACTION_WRITE);
            throw ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, "Only the author may correct a private note.");
        }
        // IL-BR-05: one level deep, so "what is currently true" is always a single hop.
        if (original.isCorrection()) {
            throw ApiException.businessRule("Corrections cannot be corrected; correct the original instead (IL-BR-05).")
                    .detail("correctsId", original.correctsId());
        }

        PanMasker.Result masked = PanMasker.mask(request.body() == null ? "" : request.body());
        Interaction correction = interactions.insert(new NewInteraction(
                UuidV7.next(),
                original.clientId(),
                InteractionType.NOTE,
                InteractionDirection.INTERNAL,
                request.subject().trim(),
                masked.body(),
                original.occurredAt(),
                null,
                InteractionOutcome.NOT_APPLICABLE,
                original.visibility(),
                InteractionSource.WEB,
                null,
                caller.id(),
                original.id()));
        events.corrected(correction, original);
        return InteractionResponse.of(
                correction,
                true,
                authorOf(correction.authorId(), caller),
                List.of(),
                masked.maskedAnything() ? masked.maskedCount() : null);
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

    private static UserSummary author(Map<UUID, UserSummary> authors, UUID id) {
        return authors.getOrDefault(id, new UserSummary(id, null));
    }

    /** IL-BR-03: what the detail view lists under an original. */
    private List<CorrectionSummary> correctionsOf(Interaction interaction) {
        if (interaction.isCorrection()) {
            return List.of();
        }
        List<Interaction> corrections = interactions.findCorrections(interaction.id());
        if (corrections.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserSummary> authors =
                directory.byIds(corrections.stream().map(Interaction::authorId).toList());
        return corrections.stream()
                .map(c -> new CorrectionSummary(c.id(), c.subject(), author(authors, c.authorId()), c.createdAt()))
                .toList();
    }

    /** §6.3: the refusal names the route that is still open, so the UI can offer it. */
    private static ApiException windowClosed(UUID id) {
        return ApiException.unprocessable(
                        InteractionErrorCodes.INTERACTION_EDIT_WINDOW_CLOSED,
                        "The 15-minute edit window has closed; record a correction instead (IL-BR-03).")
                .detail("correctionEndpoint", correctionEndpoint(id));
    }

    private static String correctionEndpoint(UUID id) {
        return "/api/v1/interactions/" + id + "/corrections";
    }

    /**
     * §4.8: the current state rides in {@code details[0].current} so the author's typing is not
     * thrown away. It is the author's own text, so this is not a disclosure to audit.
     */
    private ApiException versionConflict(Interaction current, CurrentUser caller) {
        return ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT,
                        "This interaction was changed by someone else. Review the current text and retry.")
                .detail(Map.of(
                        "current",
                        InteractionResponse.of(
                                current, true, authorOf(current.authorId(), caller), correctionsOf(current), null)));
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
        if (request.type().isInternalOnly() && request.direction() != InteractionDirection.INTERNAL) {
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
