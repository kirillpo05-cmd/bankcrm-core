package com.client360.interaction.service;

import static com.client360.common.security.Permissions.AUDIT_READ;
import static com.client360.common.security.Permissions.INTERACTION_DELETE;
import static com.client360.common.security.Permissions.INTERACTION_READ;
import static com.client360.common.security.Permissions.INTERACTION_WRITE;
import static com.client360.common.security.Permissions.TICKET_READ;
import static com.client360.common.security.Permissions.TICKET_WRITE;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.id.UuidV7;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Scope;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.Cursor;
import com.client360.common.web.KeysetPage;
import com.client360.interaction.api.AttachmentView;
import com.client360.interaction.api.CreateCorrectionRequest;
import com.client360.interaction.api.CreateInteractionRequest;
import com.client360.interaction.api.FeedFilter;
import com.client360.interaction.api.InteractionEdit;
import com.client360.interaction.api.InteractionErrorCodes;
import com.client360.interaction.api.InteractionResponse;
import com.client360.interaction.api.InteractionResponse.CorrectionSummary;
import com.client360.interaction.api.TicketFilter;
import com.client360.interaction.api.TicketPatch;
import com.client360.interaction.api.TicketQueueEntry;
import com.client360.interaction.api.TicketView;
import com.client360.interaction.api.TimelineEntry;
import com.client360.interaction.api.TimelineFilter;
import com.client360.interaction.api.UserSummary;
import com.client360.interaction.client.ClientAccessClient;
import com.client360.interaction.client.ClientAccessView;
import com.client360.interaction.client.UserDirectoryClient;
import com.client360.interaction.domain.Interaction;
import com.client360.interaction.domain.InteractionDirection;
import com.client360.interaction.domain.InteractionOutcome;
import com.client360.interaction.domain.InteractionSource;
import com.client360.interaction.domain.InteractionType;
import com.client360.interaction.domain.TicketPriority;
import com.client360.interaction.domain.TicketStatus;
import com.client360.interaction.persistence.AttachmentRepository;
import com.client360.interaction.persistence.InteractionRepository;
import com.client360.interaction.persistence.InteractionRepository.FeedQuery;
import com.client360.interaction.persistence.InteractionRepository.NewInteraction;
import com.client360.interaction.persistence.InteractionRepository.NewTicket;
import com.client360.interaction.persistence.InteractionRepository.TicketQuery;
import com.client360.interaction.persistence.InteractionRepository.TicketUpdate;
import com.client360.interaction.persistence.InteractionRepository.TimelineQuery;
import com.client360.interaction.persistence.InteractionRepository.TimelineRow;
import com.client360.interaction.support.BusinessHours;
import com.client360.interaction.support.PanMasker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Interaction Log (SPEC.md §6). */
@Service
public class InteractionService {

    /** §4.5: a feed page, not a report. */
    private static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 100;

    /** §6.3: a reason that explains nothing is the same as no reason at all. */
    private static final int MIN_REASON_LENGTH = 10;

    private final InteractionRepository interactions;
    private final ClientAccessClient clientAccess;
    private final UserDirectoryClient directory;
    private final InteractionEvents events;
    private final AttachmentRepository attachmentRepository;
    private final AccessPolicy accessPolicy;
    private final DatabaseClock clock;

    public InteractionService(
            InteractionRepository interactions,
            ClientAccessClient clientAccess,
            UserDirectoryClient directory,
            InteractionEvents events,
            AttachmentRepository attachmentRepository,
            AccessPolicy accessPolicy,
            DatabaseClock clock) {
        this.interactions = interactions;
        this.clientAccess = clientAccess;
        this.directory = directory;
        this.events = events;
        this.attachmentRepository = attachmentRepository;
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
                client.ownerManagerId(),
                client.teamId(),
                ticketDraft(request, client),
                null));

        events.created(created);
        return InteractionResponse.of(
                created,
                true,
                authorOf(created.authorId(), caller),
                ticketViewOf(created),
                // Nothing can be attached to an interaction that did not exist a moment ago.
                List.of(),
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
            UUID clientId, TimelineFilter filter, String cursor, Integer limit, CurrentUser caller) {
        clientAccess.require(clientId, INTERACTION_READ);
        int pageSize = requireLimit(limit);
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.validation("from", "must not be after to");
        }
        // §6.3: struck-through rows are for auditors and admins, who hold audit:read at ALL. A
        // supervisor holds it at TEAM and a manager not at all — asked as permission plus scope,
        // never as a role name (RB-BR-01).
        if (filter.includeDeleted() && !accessPolicy.holdsAtScopeAll(caller, AUDIT_READ)) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Deleted interactions are visible to auditors and admins only.")
                    .detail("field", "includeDeleted", "permission", AUDIT_READ);
        }

        List<TimelineRow> rows = interactions.timeline(
                new TimelineQuery(
                        clientId,
                        filter.types(),
                        filter.from(),
                        filter.to(),
                        filter.authorId(),
                        filter.q(),
                        filter.includeDeleted(),
                        Cursor.decode(cursor),
                        caller.id(),
                        isAdmin(caller)),
                // One extra row answers "is there more" without a second count over a growing feed.
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<TimelineRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<UUID> people = new java.util.ArrayList<>();
        page.forEach(row -> {
            people.add(row.interaction().authorId());
            if (row.interaction().deletedBy() != null) {
                people.add(row.interaction().deletedBy());
            }
            // Assignees join the same batch: a queue of fifty tickets must not be fifty calls to
            // the user directory, and the client already deduplicates.
            if (row.interaction().ticket() != null && row.interaction().ticket().assigneeId() != null) {
                people.add(row.interaction().ticket().assigneeId());
            }
        });
        Map<UUID, UserSummary> names = directory.byIds(people);
        // One clock read for the whole page. slaBreached is computed per row against it, so every
        // row on a page is judged against the same instant rather than a drifting one.
        Instant now = clock.now();

        List<TimelineEntry> content = page.stream()
                .map(row -> TimelineEntry.of(
                        row,
                        author(names, row.interaction().authorId()),
                        row.interaction().deletedBy() == null
                                ? null
                                : author(names, row.interaction().deletedBy()),
                        ticketViewOf(row.interaction(), names, now),
                        caller.id()))
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
                interaction,
                withBody,
                directory.byId(interaction.authorId()),
                ticketViewOf(interaction),
                // Only the detail lists them. A timeline row carries attachmentCount instead, so a
                // feed of fifty rows is not fifty listings of files nobody opened (IL-BR-11).
                attachmentsOf(interaction.id()),
                correctionsOf(interaction),
                null);
    }

    // ------------------------------------------------------------------- delete

    /**
     * §6.3 soft delete. The row stays: audit rows reference it and corrections may point at it.
     * It leaves the feed and survives in an auditor's {@code includeDeleted} view, struck through.
     *
     * <p>Managers cannot delete at all — "that is the whole point of an audit trail" — so the
     * permission is asked first and answered {@code 403 ROLE_REQUIRED} before anything is looked
     * up. It depends on the caller alone and reveals nothing about which interactions exist.
     *
     * <p>§6.3 also refuses a delete while the interaction has a linked open task. Tasks live in this
     * service but have no code yet (§7), so there is nothing to check; the rule lands with them.
     */
    @Transactional
    public void delete(UUID id, int expectedVersion, String rawReason, CurrentUser caller) {
        if (accessPolicy.scopeOf(caller, INTERACTION_DELETE).isEmpty()) {
            throw ApiException.forbidden(
                            ErrorCodes.ROLE_REQUIRED,
                            "Deleting an interaction requires the interaction:delete permission.")
                    .detail("permission", INTERACTION_DELETE);
        }
        Interaction current = interactions.findById(id).orElseThrow(InteractionService::notFound);
        clientAccess.require(current.clientId(), INTERACTION_DELETE);
        if (!current.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(current.clientId(), id, INTERACTION_DELETE);
            throw notFound();
        }

        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < MIN_REASON_LENGTH) {
            throw ApiException.businessRule("A reason of at least " + MIN_REASON_LENGTH + " characters is required.")
                    .detail("field", "reason", "issue", "must be at least " + MIN_REASON_LENGTH + " characters");
        }
        if (current.version() != expectedVersion) {
            throw versionConflict(current, caller);
        }

        Interaction deleted = interactions
                .softDelete(id, expectedVersion, caller.id(), reason)
                .orElseThrow(() ->
                        versionConflict(interactions.findById(id).orElseThrow(InteractionService::notFound), caller));
        events.deleted(deleted, reason);
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
                    current,
                    true,
                    authorOf(current.authorId(), caller),
                    ticketViewOf(current),
                    attachmentsOf(current.id()),
                    correctionsOf(current),
                    null);
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
                ticketViewOf(saved),
                attachmentsOf(saved.id()),
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
        // Kept rather than discarded: the correction is stamped with the client's current owner
        // and team from this same answer, so it lands scoped exactly as a fresh interaction does.
        ClientAccessView client = clientAccess.require(original.clientId(), INTERACTION_WRITE);
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
                client.ownerManagerId(),
                client.teamId(),
                // A correction is a NOTE, never a ticket: correcting the wording of a ticket must
                // not fork its lifecycle into a second SLA clock (IL-BR-03).
                null,
                original.id()));
        events.corrected(correction, original);
        return InteractionResponse.of(
                correction,
                true,
                authorOf(correction.authorId(), caller),
                ticketViewOf(correction),
                List.of(),
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
     * §4.8: the current state rides in {@code details[0].current} so the caller's work is not thrown
     * away. The body is included only if the caller may read it: a conflict is still a response,
     * and an admin who hits one deleting someone's private note must not receive the text IL-BR-09
     * keeps from them.
     */
    private ApiException versionConflict(Interaction current, CurrentUser caller) {
        return ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT,
                        "This interaction was changed by someone else. Review the current state and retry.")
                .detail(Map.of(
                        "current",
                        InteractionResponse.of(
                                current,
                                current.canReadBodyAs(caller.id()),
                                authorOf(current.authorId(), caller),
                                ticketViewOf(current),
                                attachmentsOf(current.id()),
                                correctionsOf(current),
                                null)));
    }

    /**
     * {@code GET /interactions} (§6.3, IL-US-07) — the supervisor's cross-client coaching feed.
     *
     * <p>Answerable at all only because V6 denormalized the owning team onto each row. The scope
     * decides the teams, never the caller: at {@code TEAM} the feed is every team in the caller's
     * scope — which since client-service gained {@code team_members} can be more than one, so a
     * supervisor covering two branches sees both — resolved from client-service because membership
     * changes and a token claim would not; at {@code ALL} an explicit {@code teamId} narrows it and
     * its absence means everyone.
     *
     * <p>{@code OWN} is refused outright. A manager's own clients are their timelines, one at a
     * time; there is nothing a cross-client feed would add for them but a list of the same rows.
     *
     * <p>IL-BR-09: {@code PRIVATE} interactions are excluded for everybody here — the author and
     * an admin included. That is stricter than the per-client timeline, deliberately.
     */
    @Transactional(readOnly = true)
    public KeysetPage<TimelineEntry> feed(FeedFilter filter, String cursor, Integer limit, CurrentUser caller) {
        Scope scope = accessPolicy
                .scopeOf(caller, INTERACTION_READ)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.PERMISSION_DENIED, "This action requires the interaction:read permission.")
                        .detail("permission", INTERACTION_READ));

        List<UUID> teamIds;
        if (scope == Scope.ALL) {
            teamIds = filter.teamId() == null ? List.of() : List.of(filter.teamId());
        } else if (scope == Scope.TEAM) {
            Set<UUID> own = directory.scopeTeamsOf();
            if (own.isEmpty()) {
                // An empty scope is "no team", never "every team". Refusing is the only reading that
                // cannot turn an unreachable client-service into an unscoped feed.
                throw ApiException.forbidden(
                        ErrorCodes.PERMISSION_DENIED,
                        "Your teams could not be resolved, so this feed cannot be scoped.");
            }
            if (filter.teamId() != null && !own.contains(filter.teamId())) {
                // Naming someone else's team is not a smaller request, it is a different one.
                throw ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, "This feed is limited to your own teams.")
                        .detail("teamIds", List.copyOf(own));
            }
            // A named team of their own narrows it; otherwise all of them.
            teamIds = filter.teamId() != null ? List.of(filter.teamId()) : List.copyOf(own);
        } else {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "The cross-client feed needs interaction:read at TEAM or ALL scope.")
                    .detail("permission", INTERACTION_READ, "scope", scope);
        }

        int pageSize = requireLimit(limit);
        List<TimelineRow> rows = interactions.crossClientFeed(
                new FeedQuery(
                        teamIds,
                        filter.clientId(),
                        filter.authorId(),
                        filter.types(),
                        filter.from(),
                        filter.to(),
                        Cursor.decode(cursor)),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<TimelineRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<UUID, UserSummary> names = directory.byIds(
                page.stream().map(row -> row.interaction().authorId()).toList());
        Instant now = clock.now();

        List<TimelineEntry> content = page.stream()
                .map(row -> TimelineEntry.of(
                        row,
                        author(names, row.interaction().authorId()),
                        null,
                        ticketViewOf(row.interaction(), names, now),
                        caller.id()))
                .toList();
        Interaction last = page.isEmpty() ? null : page.getLast().interaction();
        String nextCursor = hasMore && last != null ? new Cursor(last.occurredAt(), last.id()).encode() : null;
        return new KeysetPage<>(content, nextCursor, hasMore);
    }

    /**
     * {@code GET /tickets} (§6.3, IL-US-05) — the ticket queue, most urgent first.
     *
     * <p><strong>What this serves and what it does not.</strong> A queue by assignee is
     * authorizable here: the tickets are the caller's own, or an admin is asking. A queue across a
     * supervisor's whole team is not, and the gap is structural rather than an omission. This
     * service holds no client table, so "every ticket on a client my team owns" cannot be answered
     * without either the owner and team denormalized onto {@code interactions} — the "denormalized
     * fields on Kafka events" path, which needs a {@code client.events} consumer that does not
     * exist yet — or a per-query round trip for a client-id set with no bound on its size. The
     * spec's own index, {@code (ticket_assignee_id, sla_due_at)}, is built for the assignee queue
     * and would not serve a team scan either.
     *
     * <p>What a supervisor can do today is ask for one client's tickets, which
     * {@link ClientAccessClient} authorizes per client exactly as every other read does.
     */
    @Transactional(readOnly = true)
    public KeysetPage<TicketQueueEntry> tickets(TicketFilter filter, String cursor, Integer limit, CurrentUser caller) {
        Scope scope = accessPolicy
                .scopeOf(caller, TICKET_READ)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.PERMISSION_DENIED, "This action requires the ticket:read permission.")
                        .detail("permission", TICKET_READ));

        if (filter.clientId() != null) {
            // One client's queue is one client's authorization question, and ER-01 shapes the
            // answer: a client outside the caller's scope is 404 here as everywhere else.
            clientAccess.require(filter.clientId(), TICKET_READ);
        }

        UUID assigneeId = filter.assigneeId();
        List<UUID> teamIds = List.of();
        if (filter.clientId() == null) {
            // V6 made the team's queue answerable: the rows carry the owning team, so a supervisor
            // can be scoped to their own clients without this service holding a client table.
            if (scope == Scope.TEAM) {
                Set<UUID> own = directory.scopeTeamsOf();
                if (own.isEmpty()) {
                    // "No team", never "every team" — see the feed for why that distinction matters.
                    throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Your teams could not be resolved, so this queue cannot be scoped.");
                }
                teamIds = List.copyOf(own);
            } else if (scope == Scope.OWN && assigneeId == null) {
                // "My open tickets" — the tickets on the caller's own desk.
                assigneeId = caller.id();
            }
            if (scope == Scope.OWN && !caller.id().equals(assigneeId)) {
                // Nothing narrows an OWN caller to a colleague's desk, so the honest answer is no.
                throw ApiException.forbidden(
                                ErrorCodes.PERMISSION_DENIED,
                                "Reading another user's ticket queue requires ticket:read beyond OWN scope.")
                        .detail("permission", TICKET_READ, "assigneeId", assigneeId);
            }
        }

        int pageSize = requireLimit(limit);
        List<Interaction> rows = interactions.ticketQueue(
                new TicketQuery(
                        assigneeId,
                        filter.clientId(),
                        teamIds,
                        filter.statuses(),
                        filter.priority(),
                        filter.slaBreached(),
                        filter.stale(),
                        Cursor.decode(cursor),
                        caller.id(),
                        isAdmin(caller)),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<Interaction> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<UUID> people = new java.util.ArrayList<>();
        page.forEach(row -> {
            people.add(row.authorId());
            if (row.ticket().assigneeId() != null) {
                people.add(row.ticket().assigneeId());
            }
        });
        Map<UUID, UserSummary> names = directory.byIds(people);
        Instant now = clock.now();

        List<TicketQueueEntry> content = page.stream()
                .map(row -> TicketQueueEntry.of(row, author(names, row.authorId()), ticketViewOf(row, names, now)))
                .toList();
        Interaction last = page.isEmpty() ? null : page.getLast();
        // Keyed on the deadline, because that is what the page is ordered by. A cursor built from
        // occurredAt would page a list sorted by something else and silently skip rows.
        String nextCursor = hasMore && last != null ? new Cursor(last.ticket().slaDueAt(), last.id()).encode() : null;
        return new KeysetPage<>(content, nextCursor, hasMore);
    }

    /**
     * {@code PATCH /interactions/{id}/ticket} (IL-US-05) — a ticket transition.
     *
     * <p>No {@code If-Match}, and §6.3 lists no version conflict for this endpoint. The state
     * machine is its own guard where it matters: two callers both moving {@code NEW → IN_PROGRESS}
     * mean the second one finds the ticket already there and is refused by IL-BR-08, which is a
     * better answer than a stale-version error about a field neither of them touched.
     */
    @Transactional
    public InteractionResponse patchTicket(UUID id, TicketPatch patch, CurrentUser caller) {
        if (patch == null || patch.isEmpty()) {
            throw ApiException.validation("ticket", "at least one field must be present");
        }
        Interaction current = interactions.findById(id).orElseThrow(InteractionService::notFound);
        if (!current.isTicket()) {
            // Not a 422: saying "that exists but is a NOTE" about a row the caller has not been
            // authorized for would answer a question they never earned (ER-01).
            throw notFound();
        }
        ClientAccessView client = clientAccess.require(current.clientId(), TICKET_WRITE);
        if (!current.isVisibleTo(caller.id(), isAdmin(caller))) {
            events.recordDenial(current.clientId(), id, TICKET_WRITE);
            throw notFound();
        }

        Interaction.Ticket ticket = current.ticket();
        boolean supervises = supervisesTickets(caller);
        requireTicketActor(ticket, client, caller, supervises);

        TicketStatus target = patch.status() == null ? ticket.status() : patch.status();
        if (target != ticket.status() && !ticket.status().canMoveTo(target)) {
            throw ApiException.conflict(
                            InteractionErrorCodes.ILLEGAL_STATE_TRANSITION,
                            "A " + ticket.status() + " ticket cannot move to " + target + " (IL-BR-08).")
                    .detail(
                            "from",
                            ticket.status(),
                            "to",
                            target,
                            "legalTargets",
                            ticket.status().legalTargets());
        }

        String resolutionNote = patch.resolutionNote() == null ? ticket.resolutionNote() : patch.resolutionNote();
        if (target.requiresResolutionNote() && (resolutionNote == null || resolutionNote.isBlank())) {
            throw ApiException.businessRule("Moving to " + target + " requires a resolution note.")
                    .detail("field", "resolutionNote", "issue", "required for " + target);
        }

        TicketPriority priority = patch.priority() == null ? ticket.priority() : patch.priority();
        if (priority == TicketPriority.CRITICAL && ticket.priority() != TicketPriority.CRITICAL && !supervises) {
            // §6.3 words this as "without the supervisor role". Asked here as permission + scope
            // (CLAUDE.md rule 5): holding ticket:write beyond your own clients is what supervising
            // means, and the check keeps working when v2 replaces roles with real grants.
            throw ApiException.businessRule("Raising a ticket to CRITICAL is a supervisor's decision.")
                    .detail("field", "priority", "issue", "requires ticket:write beyond OWN scope");
        }

        Instant now = clock.now();
        return applyTransition(current, patch, target, priority, resolutionNote, client, now, caller);
    }

    /**
     * Where IL-BR-07 and IL-BR-08 actually happen: the deadline moves, or it does not, and the
     * pause is accounted for. Kept separate from the checks above so the rules are readable without
     * the validation around them.
     */
    private InteractionResponse applyTransition(
            Interaction current,
            TicketPatch patch,
            TicketStatus target,
            TicketPriority priority,
            String resolutionNote,
            ClientAccessView client,
            Instant now,
            CurrentUser caller) {
        Interaction.Ticket ticket = current.ticket();
        Instant slaDueAt = ticket.slaDueAt();
        long pausedSeconds = ticket.pausedSeconds();
        Instant waitingSince = ticket.waitingSince();

        // IL-BR-08: leaving WAITING_CLIENT gives back exactly the business hours the pause cost —
        // a ticket parked over a weekend gets nothing, because nobody was working it either way.
        if (ticket.status().pausesSla() && !target.pausesSla()) {
            long paused =
                    BusinessHours.between(waitingSince, now, client.zone()).toSeconds();
            slaDueAt = slaDueAt.plusSeconds(paused);
            pausedSeconds += paused;
            waitingSince = null;
        } else if (!ticket.status().pausesSla() && target.pausesSla()) {
            waitingSince = now;
        }

        // IL-BR-07: a raise recomputes from the original occurredAt, which may put the deadline in
        // the past — intentionally (IL-EC-09). Lowering priority does not, or a ticket could buy
        // time by being de-escalated. The pause already granted is carried across, because it was
        // earned by the client's silence and an escalation does not take it back.
        if (priority.isHigherThan(ticket.priority())) {
            slaDueAt = BusinessHours.plus(current.occurredAt(), priority.sla(), client.zone())
                    .plusSeconds(pausedSeconds);
        }

        // ck_interactions_ticket_resolved_at: the timestamp and the status agree or the row is
        // refused. Reopening a RESOLVED ticket therefore clears it rather than leaving a resolution
        // date on a ticket that is open again.
        Instant resolvedAt =
                target.isResolvedOrClosed() ? (ticket.resolvedAt() == null ? now : ticket.resolvedAt()) : null;
        Instant closedAt = target == TicketStatus.CLOSED ? (ticket.closedAt() == null ? now : ticket.closedAt()) : null;

        Interaction saved = interactions.updateTicket(
                current.id(),
                new TicketUpdate(
                        target,
                        priority,
                        patch.assigneeId() == null ? ticket.assigneeId() : patch.assigneeId(),
                        slaDueAt,
                        resolvedAt,
                        closedAt,
                        resolutionNote,
                        waitingSince,
                        pausedSeconds));

        events.ticketChanged(current, saved);
        return InteractionResponse.of(
                saved,
                saved.canReadBodyAs(caller.id()),
                authorOf(saved.authorId(), caller),
                ticketViewOf(saved),
                attachmentsOf(saved.id()),
                correctionsOf(saved),
                null);
    }

    /**
     * §6.3: the assignee, the client's owner, or a supervisor over either.
     *
     * <p>{@code clientAccess.require} has already settled whether the caller may touch this client
     * at all. This is the narrower question of who owns the work — a manager holding
     * {@code ticket:write} over their own clients may not reassign a colleague's ticket just
     * because it happens to sit on a client they share.
     */
    private void requireTicketActor(
            Interaction.Ticket ticket, ClientAccessView client, CurrentUser caller, boolean supervises) {
        boolean isAssignee = caller.id().equals(ticket.assigneeId());
        boolean isOwner = caller.id().equals(client.ownerManagerId());
        if (!isAssignee && !isOwner && !supervises) {
            throw ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED,
                            "Only the assignee, the client's owner or their supervisor may change this ticket.")
                    .detail("permission", TICKET_WRITE);
        }
    }

    /** Holding {@code ticket:write} past your own clients is what §6.3 calls supervising. */
    private boolean supervisesTickets(CurrentUser caller) {
        return accessPolicy
                .scopeOf(caller, TICKET_WRITE)
                .filter(scope -> scope != Scope.OWN)
                .isPresent();
    }

    /**
     * The ticket block for a single response, or {@code null} on anything that is not a ticket.
     *
     * <p>Reads the clock once, here, because {@code slaBreached} is computed and not stored
     * (TR-BR-02's reasoning applies to an SLA as much as to an overdue task). The timeline builds
     * its rows from one captured instant instead of calling this per row — a feed of fifty tickets
     * must not be fifty {@code SELECT now()}.
     */
    private TicketView ticketViewOf(Interaction interaction) {
        if (interaction.ticket() == null) {
            return null;
        }
        return TicketView.of(interaction.ticket(), assigneeOf(interaction.ticket()), clock.now());
    }

    private List<AttachmentView> attachmentsOf(UUID interactionId) {
        return attachmentRepository.listFor(interactionId).stream()
                .map(AttachmentView::of)
                .toList();
    }

    private UserSummary assigneeOf(Interaction.Ticket ticket) {
        return ticket.assigneeId() == null ? null : directory.byId(ticket.assigneeId());
    }

    /** The timeline's variant: names already batched, clock already read. */
    private static TicketView ticketViewOf(Interaction interaction, Map<UUID, UserSummary> names, Instant now) {
        Interaction.Ticket ticket = interaction.ticket();
        if (ticket == null) {
            return null;
        }
        UserSummary assignee = ticket.assigneeId() == null ? null : names.get(ticket.assigneeId());
        return TicketView.of(ticket, assignee, now);
    }

    /**
     * IL-BR-07: the deadline is derived from priority at creation and frozen.
     *
     * <p>Measured in the owning team's business hours, so {@code HIGH} is twenty-four working
     * hours rather than one calendar day, and a ticket raised on Friday evening does not spend its
     * SLA over a weekend nobody was working. The zone comes from client-service on the
     * authorization answer this method's caller already has — asking again would be a second hop
     * for one string.
     *
     * <p>The clock starts at {@code occurredAt}, not at now: a ticket raised from a call that
     * happened this morning is already partly through its SLA, and starting it at insert time
     * would quietly hand back the hours that passed while it was being written up.
     */
    private NewTicket ticketDraft(CreateInteractionRequest request, ClientAccessView client) {
        if (request.ticket() == null) {
            return null;
        }
        TicketPriority priority = request.ticket().priority();
        Instant slaDueAt = BusinessHours.plus(request.occurredAt(), priority.sla(), client.zone());
        return new NewTicket(priority, request.ticket().assigneeId(), slaDueAt);
    }

    private void validate(CreateInteractionRequest request, ClientAccessView client) {
        // CP-BR-08: a closed client accepts no new interaction except a note — the manager may
        // still need to record why it is closed.
        if (client.isClosed() && request.type() != InteractionType.NOTE) {
            throw ApiException.businessRule("A closed client accepts only NOTE interactions (CP-BR-08).")
                    .detail("field", "type", "issue", "client is CLOSED");
        }
        // ck_interactions_ticket_fields makes the block and the type inseparable; saying so here
        // names the field instead of surfacing a constraint (CLAUDE.md rule 9, both edges).
        if (request.type() == InteractionType.TICKET && request.ticket() == null) {
            throw ApiException.validation("ticket", "required when type is TICKET");
        }
        if (request.type() != InteractionType.TICKET && request.ticket() != null) {
            throw ApiException.validation("ticket", "only a TICKET carries a ticket block");
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

    /** IL-BR-09's admin, for callers outside this class that need the same question answered. */
    public boolean callerIsAdmin(CurrentUser caller) {
        return isAdmin(caller);
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

    static ApiException notFound() {
        return ApiException.notFound(
                InteractionErrorCodes.INTERACTION_NOT_FOUND, "Interaction not found or not visible to you.");
    }
}
