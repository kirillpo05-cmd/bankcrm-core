package com.client360.client.service;

import static com.client360.common.security.Permissions.CLIENT_DELETE;
import static com.client360.common.security.Permissions.CLIENT_KYC;
import static com.client360.common.security.Permissions.CLIENT_MERGE;
import static com.client360.common.security.Permissions.CLIENT_READ;
import static com.client360.common.security.Permissions.CLIENT_REASSIGN;
import static com.client360.common.security.Permissions.CLIENT_WRITE;

import com.client360.client.api.ClientAccessView;
import com.client360.client.api.ClientAssembler;
import com.client360.client.api.ClientErrorCodes;
import com.client360.client.api.ClientListQuery;
import com.client360.client.api.ClientPatch;
import com.client360.client.api.ClientPatch.Field;
import com.client360.client.api.ClientResponse;
import com.client360.client.api.ClientSummaryResponse;
import com.client360.client.api.CreateClientRequest;
import com.client360.client.api.KycTransitionRequest;
import com.client360.client.api.LookupResponse;
import com.client360.client.api.LookupResponse.MatchType;
import com.client360.client.api.MergeRequest;
import com.client360.client.api.MergeResponse;
import com.client360.client.api.ReassignRequest;
import com.client360.client.api.ReassignResponse;
import com.client360.client.domain.Client;
import com.client360.client.domain.ClientProduct;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.KycStatus;
import com.client360.client.domain.ProductStatus;
import com.client360.client.persistence.AccessGrantRepository;
import com.client360.client.persistence.ClientProductRepository;
import com.client360.client.persistence.ClientRepository;
import com.client360.client.persistence.ClientRepository.ClientSearch;
import com.client360.client.persistence.ClientRepository.NewClient;
import com.client360.client.persistence.ClientRepository.SortField;
import com.client360.client.persistence.TeamRepository;
import com.client360.client.persistence.UserRepository;
import com.client360.client.support.Emails;
import com.client360.client.support.PhoneNumbers;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.id.UuidV7;
import com.client360.common.ratelimit.RateLimiter;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import com.client360.common.time.DatabaseClock;
import com.client360.common.web.OffsetPage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Client Profile (SPEC.md §5). */
@Service
public class ClientService {

    /** A disambiguation list, not a report. Beyond this the manager should refine the query. */
    private static final int LOOKUP_LIMIT = 25;
    /** §4.10: the PII-enumeration guard on lookup — 60 a minute, per user. */
    private static final int LOOKUP_LIMIT_PER_MINUTE = 60;

    private static final String LOOKUP_BUCKET = "clients.lookup";

    private static final int MIN_QUERY_LENGTH = 3;

    /** §5.3: a reason that explains nothing is the same as no reason at all. */
    private static final int MIN_REASON_LENGTH = 10;

    private final ClientRepository clients;
    private final ClientProductRepository products;
    private final UserRepository users;
    private final TeamRepository teams;
    private final ClientAccess access;
    private final ClientEvents events;
    private final ClientAssembler assembler;
    private final PhoneNumbers phones;
    private final DatabaseClock clock;
    private final RateLimiter rateLimiter;

    public ClientService(
            ClientRepository clients,
            ClientProductRepository products,
            UserRepository users,
            TeamRepository teams,
            ClientAccess access,
            ClientEvents events,
            ClientAssembler assembler,
            PhoneNumbers phones,
            DatabaseClock clock,
            RateLimiter rateLimiter) {
        this.clients = clients;
        this.products = products;
        this.users = users;
        this.teams = teams;
        this.access = access;
        this.events = events;
        this.assembler = assembler;
        this.phones = phones;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------------------- create

    /**
     * {@code POST /clients} (§5.3). Joins the transaction {@code IdempotencyService} opened, so
     * the client row, the outbox event and the stored idempotent response commit or roll back
     * together (CLAUDE.md rule 3).
     */
    @Transactional
    public ClientResponse create(CreateClientRequest request, CurrentUser caller) {
        Scope scope = access.requireScope(caller, CLIENT_WRITE);
        // A manager holds client:write at OWN scope: they may create clients for themselves and
        // nobody else (§5.3, 403 on setting ownerManagerId to someone else).
        if (scope == Scope.OWN && !caller.id().equals(request.ownerManagerId())) {
            throw ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, "You can only create clients you will own.")
                    .detail("field", "ownerManagerId", "issue", "must be your own user id at this scope");
        }

        UserRepository.UserRef owner = users.findById(request.ownerManagerId())
                .filter(UserRepository.UserRef::isActive)
                .orElseThrow(() -> ApiException.businessRule("The owning manager does not exist or is not active.")
                        .detail("field", "ownerManagerId", "issue", "unknown or deactivated user"));
        // CP-BR-03: team_id is derived from the owner's primary team and never set independently.
        if (owner.primaryTeamId() == null) {
            throw ApiException.businessRule(
                            "The owning manager has no primary team, so the client has no team (CP-BR-03).")
                    .detail("field", "ownerManagerId", "issue", "user has no primary team");
        }

        String email = Emails.normalize(request.email());
        String phone = phones.normalize(request.phone(), "phone");
        String taxId = blankToNull(request.taxId());

        // Pre-checked so the caller gets details[0].conflictingId. The partial unique indexes are
        // still what makes it true under a race — ClientConstraintErrors maps those (rule 9).
        requireUnique(
                clients.findByExternalRef(request.externalRef()),
                ClientErrorCodes.CLIENT_DUPLICATE_EXTERNAL_REF,
                "A client with this external reference already exists.",
                caller);
        if (email != null) {
            requireUnique(
                    clients.findByEmail(email),
                    ClientErrorCodes.CLIENT_DUPLICATE_EMAIL,
                    "A client with this email address already exists.",
                    caller);
        }
        if (taxId != null) {
            requireUnique(
                    clients.findByTaxId(taxId),
                    ClientErrorCodes.CLIENT_DUPLICATE_TAX_ID,
                    "A client with this tax ID already exists.",
                    caller);
        }

        Client created = clients.insert(new NewClient(
                UuidV7.next(),
                request.externalRef().trim(),
                request.firstName().trim(),
                request.lastName().trim(),
                blankToNull(request.middleName()),
                request.dateOfBirth(),
                email,
                phone,
                taxId,
                blankToNull(request.address()),
                request.preferredChannel(),
                request.segment(),
                owner.id(),
                owner.primaryTeamId(),
                caller.id()));

        events.created(created);
        return assembler.toResponse(created, caller, clock.now());
    }

    // --------------------------------------------------------------------- read

    /**
     * CP-US-02. Returns decrypted PII, so it audits a {@code READ_SENSITIVE} disclosure
     * (CP-BR-13) — which is why a read needs a transaction at all.
     *
     * @throws ApiException {@code 404} when absent, deleted or out of scope (ER-01);
     *     {@code 410 CLIENT_MERGED} with a {@code Location} to the survivor for a merged record
     */
    @Transactional
    public ClientResponse read(UUID id, CurrentUser caller) {
        Client client = requireLive(clients.findById(id), id);
        Optional<AccessGrantRepository.Grant> grant = requireReadable(client, caller);
        // AT-BR-13: a disclosure outside normal scope says so, and says which grant allowed it.
        grant.ifPresent(access::recordGrantUse);
        events.readSensitive(client.id(), "client.card", grant.orElse(null));
        return assembler.toResponse(client, caller, clock.now());
    }

    // ------------------------------------------------------------------- update

    /**
     * CP-US-03: correct a client's details during a call, optimistic-locked and audited.
     *
     * <p>Checks run from the most fundamental to the most specific, so a caller is never told
     * about a stale version on an edit they were never allowed to make: existence and scope
     * ({@code 404}), field authority ({@code 403}), version ({@code 409}), business rules
     * ({@code 422}), uniqueness ({@code 409}). {@code 412} and {@code 400} are settled in the
     * controller, before a record is read at all.
     */
    @Transactional
    public ClientResponse update(UUID id, int expectedVersion, ClientPatch patch, CurrentUser caller) {
        Client current = requireLive(clients.findById(id), id);
        requireCovering(current, caller, CLIENT_WRITE);
        requireFieldAuthority(current, patch, caller);

        if (current.version() != expectedVersion) {
            throw versionConflict(current, caller);
        }
        if (patch.has(Field.EXTERNAL_REF)) {
            throw ApiException.businessRule("externalRef cannot be changed after creation (CP-BR-01).")
                    .detail("field", "externalRef", "issue", "immutable");
        }
        if (current.isClosed() && !isReopening(patch)) {
            throw ApiException.businessRule(
                            "A closed client accepts no edits except reopening it to ACTIVE (CP-BR-08).")
                    .detail("status", current.status().name());
        }

        Client next = current.withProfile(
                trimmed(patch.valueOr(Field.FIRST_NAME, current.firstName())),
                trimmed(patch.valueOr(Field.LAST_NAME, current.lastName())),
                blankToNull(patch.valueOr(Field.MIDDLE_NAME, current.middleName())),
                patch.valueOr(Field.DATE_OF_BIRTH, current.dateOfBirth()),
                patch.has(Field.EMAIL) ? Emails.normalize(patch.value(Field.EMAIL)) : current.email(),
                patch.has(Field.PHONE) ? phones.normalize(patch.value(Field.PHONE), "phone") : current.phone(),
                blankToNull(patch.valueOr(Field.TAX_ID, current.taxId())),
                blankToNull(patch.valueOr(Field.ADDRESS, current.address())),
                patch.valueOr(Field.PREFERRED_CHANNEL, current.preferredChannel()),
                patch.valueOr(Field.SEGMENT, current.segment()),
                patch.valueOr(Field.STATUS, current.status()),
                patch.valueOr(Field.RISK, current.risk()));

        // Nothing changed, so nothing is written: no version bump, no event. CP-BR-12's one event
        // per write counts writes, and an audit row for a no-op is noise in a seven-year log.
        if (next.equals(current)) {
            return assembler.toResponse(current, caller, clock.now());
        }

        if (next.email() != null && !Objects.equals(next.email(), current.email())) {
            requireUnique(
                    clients.findByEmail(next.email())
                            .filter(other -> !other.id().equals(id)),
                    ClientErrorCodes.CLIENT_DUPLICATE_EMAIL,
                    "A client with this email address already exists.",
                    caller);
        }
        if (next.taxId() != null && !Objects.equals(next.taxId(), current.taxId())) {
            requireUnique(
                    clients.findByTaxId(next.taxId())
                            .filter(other -> !other.id().equals(id)),
                    ClientErrorCodes.CLIENT_DUPLICATE_TAX_ID,
                    "A client with this tax ID already exists.",
                    caller);
        }

        // The check above is advisory; this WHERE version = :expected is what actually closes the
        // race between two managers saving at once (CP-EC-01).
        Client saved = clients.update(next, expectedVersion, caller.id())
                .orElseThrow(() -> versionConflict(requireLive(clients.findById(id), id), caller));
        events.updated(current, saved);
        return assembler.toResponse(saved, caller, clock.now());
    }

    // ----------------------------------------------------------------- internal

    /**
     * The authorization question another service asks (§3.1). Returns the client's
     * authorization-relevant state when the caller holds {@code permission} over it, and
     * {@code 404} otherwise — the same answer, and the same audit row, as any other out-of-scope
     * read (ER-01, RB-BR-13).
     *
     * <p>No {@code READ_SENSITIVE}: nothing decrypted leaves here, so there is no disclosure to
     * record (CP-BR-13).
     */
    @Transactional
    public ClientAccessView checkAccess(UUID clientId, String permission, CurrentUser caller) {
        // A caller-supplied permission code is still input. Anything outside the §9.2.8 matrix is
        // a bug in the calling service, not a reason to answer a question nobody defined.
        if (!Permissions.ALL.contains(permission)) {
            throw ApiException.validation("permission", "must be a permission code from the §9.2.8 matrix");
        }
        // Not holding the permission at any scope is 403, answered before the record is looked up:
        // it depends on the caller alone, so it says nothing about which clients exist (ER-01).
        // Only once the permission is held does "not this client" collapse into 404.
        Scope scope = access.requireScope(caller, permission);
        Client client = requireLive(clients.findById(clientId), clientId);
        ClientAccess.Decision decision = access.authorize(scope, client, caller, permission);
        if (!decision.allowed()) {
            events.recordDenial(client.id(), permission);
            throw ClientAccess.notFound();
        }
        // The calling service asks this immediately before acting, so a yes here is an action taken
        // under the grant rather than a speculative check (AT-BR-13).
        decision.byGrant().ifPresent(access::recordGrantUse);
        return new ClientAccessView(
                client.id(),
                client.ownerManagerId(),
                client.teamId(),
                client.status(),
                client.kycStatus(),
                teams.timezoneOf(client.teamId()).orElse(null));
    }

    // ------------------------------------------------------------------- merge

    /**
     * {@code POST /clients/merge} (CP-US-06). Admin only, and irreversible through the API
     * (CP-BR-10, Q-08).
     *
     * <p>The order matters and is not arbitrary: the loser is marked merged — which soft-deletes it
     * — <em>before</em> the survivor adopts any of its fields. Email and tax ID are unique among
     * non-deleted clients (CP-BR-02), so handing the survivor the loser's email while the loser
     * still holds it would collide with the very index that makes duplicates findable.
     *
     * <p>What this transaction does not do is move interactions. They belong to interaction-service
     * and no transaction spans both, so they follow the {@code client.merged} event. The response
     * says so rather than reporting a number this service cannot know; until the consumer catches
     * up the loser's history is still reachable, because the {@code 410} carries a {@code Location}
     * to the survivor.
     */
    @Transactional
    public MergeResponse merge(MergeRequest request, CurrentUser caller) {
        if (request.survivorId().equals(request.mergedId())) {
            throw ApiException.businessRule("A client cannot be merged into itself.")
                    .detail("field", "mergedId", "issue", "must differ from survivorId");
        }
        access.scopeOf(caller, CLIENT_MERGE)
                .orElseThrow(() -> ApiException.forbidden(
                                ErrorCodes.ROLE_REQUIRED,
                                "Merging clients requires the " + CLIENT_MERGE + " permission.")
                        .detail("permission", CLIENT_MERGE));

        // Loaded including merged records, deliberately. requireLive answers a merged id with the
        // 410 a read deserves, but a merge is a write whose precondition failed — §5.3 asks for
        // 409 here, and going through the read path would make that answer unreachable.
        Client survivor = mergeCandidate(request.survivorId());
        Client loser = mergeCandidate(request.mergedId());
        requireCovering(survivor, caller, CLIENT_MERGE);
        requireCovering(loser, caller, CLIENT_MERGE);

        // CP-EC-09 allows chains, so being a survivor already is fine; being a loser already is
        // not — that record has stopped being a record and has no history left to give.
        if (survivor.isMerged() || loser.isMerged()) {
            throw ApiException.conflict(
                            ErrorCodes.ILLEGAL_STATE_TRANSITION,
                            "A record that was already merged cannot be merged again.")
                    .detail("survivorMerged", survivor.isMerged(), "mergedMerged", loser.isMerged());
        }

        List<ClientProduct> loserProducts = products.findByClient(loser.id(), null, null);
        List<ClientProduct> movable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ClientProduct product : loserProducts) {
            Optional<ClientProduct> clash = products.findByExternalId(survivor.id(), product.externalProductId());
            if (clash.isEmpty()) {
                movable.add(product);
                continue;
            }
            // §5.3: two ACTIVE records of the same core-banking product is broken data, not debris
            // for a merge to tidy — refuse the whole thing and let a human look. CP-BR-10's "skip
            // and report" is for the rest, where a duplicate of a closed account is exactly what
            // deduplication is meant to clear away.
            if (product.status() == ProductStatus.ACTIVE && clash.get().status() == ProductStatus.ACTIVE) {
                throw ApiException.businessRule(
                                "Both clients hold an ACTIVE product with the same external id; resolve it in core banking first.")
                        .detail("externalProductId", product.externalProductId());
            }
            skipped.add(product.externalProductId());
        }

        Client mergedLoser = clients.markMerged(loser.id(), survivor.id(), loser.version(), caller.id())
                .orElseThrow(() -> versionConflict(loser, caller));
        Client adopted = applyFieldResolution(survivor, loser, request, caller);
        movable.forEach(product -> products.repointToSurvivor(product.id(), survivor.id()));

        events.merged(mergedLoser, adopted, request.reason(), movable.size(), skipped);
        return new MergeResponse(
                assembler.toResponse(adopted, caller, clock.now()),
                new MergeResponse.Moved(movable.size()),
                new MergeResponse.Skipped(List.copyOf(skipped)),
                // Tasks are v3 (§11.1), so there is nothing of theirs to wait for yet.
                List.of("interactions"),
                loser.id());
    }

    /** Absent or soft-deleted is {@code 404}; already merged falls through to the {@code 409}. */
    private Client mergeCandidate(UUID id) {
        return clients.findAnyById(id)
                .filter(client -> client.isMerged() || client.deletedAt() == null)
                .orElseThrow(ClientAccess::notFound);
    }

    /**
     * The survivor adopts whichever contested fields the caller asked for (CP-US-06).
     *
     * <p>Values travel as decrypted domain fields and are re-encrypted by the repository on the way
     * back down, so nothing here copies ciphertext between rows and the survivor's {@code
     * key_version} stays its own.
     */
    private Client applyFieldResolution(Client survivor, Client loser, MergeRequest request, CurrentUser caller) {
        if (request.fieldResolution().values().stream().noneMatch(source -> source == MergeRequest.Source.MERGED)) {
            return survivor;
        }
        Client adopted = survivor.withProfile(
                survivor.firstName(),
                survivor.lastName(),
                request.takesFromMerged(MergeRequest.Field.MIDDLE_NAME) ? loser.middleName() : survivor.middleName(),
                request.takesFromMerged(MergeRequest.Field.DATE_OF_BIRTH)
                        ? loser.dateOfBirth()
                        : survivor.dateOfBirth(),
                request.takesFromMerged(MergeRequest.Field.EMAIL) ? loser.email() : survivor.email(),
                request.takesFromMerged(MergeRequest.Field.PHONE) ? loser.phone() : survivor.phone(),
                request.takesFromMerged(MergeRequest.Field.TAX_ID) ? loser.taxId() : survivor.taxId(),
                request.takesFromMerged(MergeRequest.Field.ADDRESS) ? loser.address() : survivor.address(),
                survivor.preferredChannel(),
                survivor.segment(),
                // Status and risk are not the caller's to change here: each has its own path, and
                // a deduplication must not become a back door into either.
                survivor.status(),
                survivor.risk());
        return clients.update(adopted, survivor.version(), caller.id())
                .orElseThrow(() -> versionConflict(survivor, caller));
    }

    // --------------------------------------------------------------- ownership

    /**
     * CP-US-05: coverage survives holidays and departures. The team is re-derived from the new
     * owner in the same transaction, never set independently (CP-BR-03).
     */
    @Transactional
    public ReassignResponse reassign(UUID id, ReassignRequest request, CurrentUser caller) {
        Client current = requireLive(clients.findById(id), id);
        Scope scope = access.scopeOf(caller, CLIENT_REASSIGN)
                .orElseThrow(() -> deny(
                        current,
                        CLIENT_REASSIGN,
                        ApiException.forbidden(
                                        ErrorCodes.PERMISSION_DENIED,
                                        "Reassigning a client requires the " + CLIENT_REASSIGN + " permission.")
                                .detail("permission", CLIENT_REASSIGN)));
        if (!access.covers(scope, current, caller, CLIENT_REASSIGN)) {
            events.recordDenial(current.id(), CLIENT_REASSIGN);
            throw ClientAccess.notFound();
        }

        String reason = requireReason(request.reason(), "reason");
        UserRepository.UserRef newOwner = users.findById(request.newOwnerManagerId())
                .filter(UserRepository.UserRef::isActive)
                .orElseThrow(() -> ApiException.notFound(
                        ClientErrorCodes.USER_NOT_FOUND, "That manager does not exist or is deactivated."));

        if (newOwner.id().equals(current.ownerManagerId())) {
            throw ApiException.businessRule("This client already belongs to that manager.")
                    .detail("field", "newOwnerManagerId", "issue", "already the owner");
        }
        // CP-BR-03 has no team to derive without one, so the move would leave the client teamless.
        if (newOwner.primaryTeamId() == null) {
            throw ApiException.businessRule("The new owner has no primary team, so the client would have none.")
                    .detail("field", "newOwnerManagerId", "issue", "user has no primary team");
        }
        // A supervisor covers their own team, in both directions: they may not push a client onto
        // a manager they do not supervise.
        if (scope == Scope.TEAM
                && !access.teamOf(caller)
                        .map(team -> team.equals(newOwner.primaryTeamId()))
                        .orElse(false)) {
            throw deny(
                    current,
                    CLIENT_REASSIGN,
                    ApiException.forbidden(
                                    ErrorCodes.SCOPE_VIOLATION, "That manager is outside the team you supervise.")
                            .detail("field", "newOwnerManagerId", "issue", "outside your team"));
        }

        Client saved = clients.reassign(id, newOwner.id(), newOwner.primaryTeamId(), current.version(), caller.id())
                .orElseThrow(() -> versionConflict(requireLive(clients.findById(id), id), caller));
        events.reassigned(current, saved, reason);
        // Tasks live in interaction-service, which has no code yet, so nothing can have followed
        // the client. The count is reported rather than omitted so the shape does not change later.
        return new ReassignResponse(assembler.toResponse(saved, caller, clock.now()), 0);
    }

    /**
     * §4.9 soft delete. The row stays so audit rows keep resolving; every read path filters on
     * {@code deleted_at IS NULL}, so the client is gone as far as the API is concerned.
     */
    @Transactional
    public void softDelete(UUID id, int expectedVersion, String rawReason, CurrentUser caller) {
        Client current = requireLive(clients.findById(id), id);
        Scope scope = access.scopeOf(caller, CLIENT_DELETE)
                .orElseThrow(() -> deny(
                        current,
                        CLIENT_DELETE,
                        ApiException.forbidden(
                                        ErrorCodes.ROLE_REQUIRED,
                                        "Deleting a client requires the " + CLIENT_DELETE + " permission.")
                                .detail("permission", CLIENT_DELETE)));
        if (!access.covers(scope, current, caller, CLIENT_DELETE)) {
            events.recordDenial(current.id(), CLIENT_DELETE);
            throw ClientAccess.notFound();
        }

        String reason = requireReason(rawReason, "reason");
        if (current.version() != expectedVersion) {
            throw versionConflict(current, caller);
        }
        // CP-BR-09: deleting a client who still holds a live banking relationship would orphan it.
        // Close or reassign first.
        if (products.hasActiveProduct(id)) {
            throw ApiException.businessRule("This client holds an active product; close it first (CP-BR-09).")
                    .detail("blocker", "activeProducts");
        }

        Client deleted = clients.softDelete(id, expectedVersion, caller.id(), reason)
                .orElseThrow(() -> versionConflict(requireLive(clients.findById(id), id), caller));
        events.deleted(deleted, reason);
    }

    // ---------------------------------------------------------------------- kyc

    /**
     * CP-BR-04 transitions under CP-BR-05's separation of duties. The row is locked for the length
     * of the transaction: two approvers acting at once must not both see {@code PENDING} and both
     * write a decision.
     */
    @Transactional
    public ClientResponse transitionKyc(UUID id, KycTransitionRequest request, CurrentUser caller) {
        Client current = requireLive(clients.findByIdForUpdate(id), id);
        requireReadable(current, caller);
        KycStatus target = request.targetStatus();

        // No caller ever reaches these, so say so before asking about permissions.
        if (target == KycStatus.NOT_STARTED || target == KycStatus.EXPIRED) {
            throw illegalKycTarget(current.kycStatus(), target);
        }

        if (target.requiresKycPermission()) {
            // CP-BR-05: VERIFIED and REJECTED need client:kyc, which a manager does not hold.
            Scope kycScope = access.scopeOf(caller, CLIENT_KYC)
                    .orElseThrow(() -> deny(
                            current,
                            CLIENT_KYC,
                            ApiException.forbidden(
                                            ErrorCodes.ROLE_REQUIRED,
                                            "Setting KYC to " + target
                                                    + " requires the client:kyc permission (CP-BR-05).")
                                    .detail("permission", CLIENT_KYC)));
            if (!access.covers(kycScope, current, caller, CLIENT_KYC)) {
                throw deny(current, CLIENT_KYC, outOfScope(CLIENT_KYC));
            }
            // Holding the permission is not enough: nobody approves their own client. Checked
            // against the owner at request time, not at login (CP-BR-05).
            if (caller.id().equals(current.ownerManagerId())) {
                throw deny(
                        current,
                        CLIENT_KYC,
                        ApiException.forbidden(
                                        ErrorCodes.PERMISSION_DENIED,
                                        "You cannot approve or reject KYC for a client you own (CP-BR-05).")
                                .detail("reason", ClientErrorCodes.SELF_APPROVAL_FORBIDDEN));
            }
        } else {
            // Moving to PENDING is an ordinary edit: a manager starts their own client's review.
            boolean mayWrite = access.scopeOf(caller, CLIENT_WRITE)
                    .filter(scope -> access.covers(scope, current, caller, CLIENT_WRITE))
                    .isPresent();
            if (!mayWrite) {
                throw deny(current, CLIENT_WRITE, outOfScope(CLIENT_WRITE));
            }
        }

        current.kycStatus().requireTransitionTo(target);

        String note = blankToNull(request.note());
        Client next =
                switch (target) {
                    case PENDING -> current.withKyc(KycStatus.PENDING, null, null, null, note);
                    case VERIFIED -> verified(current, request, note);
                    case REJECTED -> {
                        String reason = blankToNull(request.reason());
                        if (reason == null) {
                            throw ApiException.businessRule("Rejecting KYC requires a reason.")
                                    .detail("field", "reason", "issue", "required when targetStatus is REJECTED");
                        }
                        yield current.withKyc(KycStatus.REJECTED, null, null, reason, note);
                    }
                    default -> throw illegalKycTarget(current.kycStatus(), target);
                };

        Client saved = clients.update(next, current.version(), caller.id())
                // The row is locked FOR UPDATE, so its version cannot have moved underneath us.
                .orElseThrow(() -> new IllegalStateException("Locked client " + id + " changed version"));
        events.kycChanged(current, saved);
        return assembler.toResponse(saved, caller, clock.now());
    }

    /**
     * {@code verifiedOn} is the date on the documents — a business date supplied by the approver,
     * not a clock reading. Rule 7 governs "now", and "now" still comes from PostgreSQL.
     */
    private Client verified(Client current, KycTransitionRequest request, String note) {
        LocalDate verifiedOn = request.verifiedOn();
        Integer months = request.validityMonths();
        if (verifiedOn == null || months == null) {
            throw ApiException.businessRule("Verifying KYC requires verifiedOn and validityMonths.")
                    .detail(
                            "field",
                            verifiedOn == null ? "verifiedOn" : "validityMonths",
                            "issue",
                            "required when targetStatus is VERIFIED");
        }
        if (months < 6 || months > 60) {
            throw ApiException.businessRule("KYC validity must be between 6 and 60 months.")
                    .detail("field", "validityMonths", "issue", "must be between 6 and 60");
        }
        if (verifiedOn.isAfter(clock.today())) {
            throw ApiException.businessRule("KYC cannot be verified on a future date.")
                    .detail("field", "verifiedOn", "issue", "must not be in the future");
        }
        Instant verifiedAt = verifiedOn.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant expiresAt =
                verifiedOn.plusMonths(months).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Accepting this would store a VERIFIED client that the nightly sweep expires hours later
        // (CP-BR-06) — a green badge that is already false.
        if (!expiresAt.isAfter(clock.now())) {
            throw ApiException.businessRule("This verification has already expired; record a current one.")
                    .detail("field", "verifiedOn", "issue", "verifiedOn plus validityMonths is in the past");
        }
        return current.withKyc(KycStatus.VERIFIED, verifiedAt, expiresAt, null, note);
    }

    private static ApiException illegalKycTarget(KycStatus from, KycStatus to) {
        String why =
                to == KycStatus.EXPIRED ? " EXPIRED is set only by the nightly expiry job (CP-BR-06)." : " (CP-BR-04)";
        return new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCodes.ILLEGAL_STATE_TRANSITION,
                        "KYC status cannot move from " + from + " to " + to + "." + why)
                .detail("from", from.name(), "to", to.name());
    }

    // --------------------------------------------------------------------- list

    /**
     * {@code GET /clients} (§5.3): the scoped admin list behind the search screen.
     *
     * <p>Scope is pushed into the query rather than applied to the rows that come back. Filtering
     * afterwards would make a page of 25 mean "25 rows read, some of which you may see", and would
     * lift records out of the database that the caller has no right to (RB-BR-02).
     *
     * <p>Offset pagination is right here and wrong for the timeline: this list is bounded and
     * sortable, while an interaction feed drifts under concurrent writes (§4.5).
     */
    @Transactional(readOnly = true)
    public OffsetPage<ClientSummaryResponse> list(ClientListQuery query, CurrentUser caller) {
        Scope scope = access.requireScope(caller, CLIENT_READ);
        int page = requirePage(query.page());
        int size = requireSize(query.size());
        Sorting sorting = parseSort(query.sort());

        UUID scopeOwnerId = scope == Scope.OWN ? caller.id() : null;
        List<UUID> scopeTeamIds = null;
        if (scope == Scope.TEAM) {
            // Every team in scope, not just the primary one: a supervisor covering two branches
            // supervises both (RB-BR-02), and a list that showed one of them would disagree with
            // what `covers` lets the same caller open.
            Set<UUID> own = access.scopeTeamsOf(caller);
            if (own.isEmpty()) {
                // TEAM scope with no team resolves to nothing. Leaving the filter off would quietly
                // widen the search to every client, which is the opposite of what the scope says.
                return new OffsetPage<>(List.of(), page, size, 0, 0, false);
            }
            scopeTeamIds = List.copyOf(own);
        }

        ClientSearch criteria = new ClientSearch(
                blankToNull(query.q()),
                query.segment(),
                query.status(),
                query.kycStatus(),
                query.ownerId(),
                query.teamId(),
                requireExpiringDays(query.kycExpiringWithinDays()),
                scopeOwnerId,
                scopeTeamIds);

        long total = clients.countSearch(criteria);
        List<ClientSummaryResponse> content =
                clients.search(criteria, sorting.field(), sorting.ascending(), size, page * size).stream()
                        // Everything returned is in scope by construction, and contacts stay masked: a
                        // list is not a card, so it discloses nothing and audits nothing (CP-BR-13).
                        .map(client -> assembler.toSummary(client, true))
                        .toList();
        int totalPages = (int) Math.ceil((double) total / size);
        return new OffsetPage<>(content, page, size, total, totalPages, (long) (page + 1) * size < total);
    }

    private static int requirePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw ApiException.validation("page", "must be zero or greater");
        }
        return page;
    }

    private static int requireSize(Integer size) {
        if (size == null) {
            return OffsetPage.DEFAULT_SIZE;
        }
        if (size < 1 || size > OffsetPage.MAX_SIZE) {
            throw ApiException.validation("size", "must be between 1 and " + OffsetPage.MAX_SIZE);
        }
        return size;
    }

    private static Integer requireExpiringDays(Integer days) {
        if (days != null && days < 1) {
            throw ApiException.validation("kycExpiringWithinDays", "must be one or more");
        }
        return days;
    }

    /** {@code sort=lastName,desc}. An unknown field is {@code 400}, never a silent default. */
    private static Sorting parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return new Sorting(SortField.LAST_NAME, true);
        }
        String[] parts = sort.split(",", -1);
        SortField field = SortField.byApiName(parts[0].trim())
                .orElseThrow(() -> ApiException.validation(
                        "sort",
                        "must be one of "
                                + Arrays.stream(SortField.values())
                                        .map(SortField::apiName)
                                        .toList()));
        if (parts.length == 1) {
            return new Sorting(field, true);
        }
        if (parts.length > 2) {
            throw ApiException.validation("sort", "expected <field> or <field>,asc|desc");
        }
        return switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> new Sorting(field, true);
            case "desc" -> new Sorting(field, false);
            default -> throw ApiException.validation("sort", "direction must be asc or desc");
        };
    }

    private record Sorting(SortField field, boolean ascending) {}

    // ------------------------------------------------------------------- lookup

    /**
     * CP-US-01, the 2-minute path: one box, one round trip, straight to the card when the answer
     * is unambiguous.
     *
     * <p>Exactly one search parameter is required. {@code q} is auto-detected; the typed
     * parameters are not, so a caller who knows what they hold can say so.
     */
    @Transactional
    public LookupResponse lookup(String email, String phone, String externalRef, String q, CurrentUser caller) {
        Scope scope = access.requireScope(caller, CLIENT_READ);
        // §4.10: 60 a minute per user. This endpoint answers "does a client with this email exist"
        // across the whole book — the narrow ER-01 exception that lets a manager raise a
        // break-glass request (RB-US-05) — so it is also the one endpoint an attacker would use to
        // enumerate customers. Counted before the query runs, and outside this transaction, so a
        // probe that ends in an error still costs the prober a request.
        rateLimiter.check(caller.id().toString(), LOOKUP_BUCKET, LOOKUP_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
        Query query = parse(email, phone, externalRef, q);

        List<Client> found =
                switch (query.type()) {
                    case EMAIL ->
                        clients.findByEmail(query.value()).map(List::of).orElseGet(List::of);
                    case EXTERNAL_REF ->
                        clients.findByExternalRef(query.value()).map(List::of).orElseGet(List::of);
                    case PHONE -> clients.findAllByPhone(query.value(), LOOKUP_LIMIT);
                    case NAME -> clients.searchByName(query.value(), LOOKUP_LIMIT);
                };

        List<ClientSummaryResponse> matches = new ArrayList<>();
        boolean disclosedOutOfScope = false;
        for (Client client : found) {
            boolean inScope = access.covers(scope, client, caller, CLIENT_READ);
            if (!inScope) {
                // The narrow ER-01 exception covers exact identifier hits only. A name search must
                // not become a way to enumerate other teams' clients.
                if (query.type() == MatchType.NAME) {
                    continue;
                }
                disclosedOutOfScope = true;
            }
            matches.add(assembler.toSummary(client, inScope));
        }
        if (disclosedOutOfScope) {
            // Telling a caller that a record exists is itself a disclosure, and it is the entry
            // point to a break-glass request — so it is audited like one (RB-US-05).
            matches.stream()
                    .filter(match -> !match.inScope())
                    .forEach(match -> events.readSensitive(match.id(), "lookup.existence"));
        }

        // CP-EC-03: a shared landline yields two people, and the UI must disambiguate rather than
        // jump straight to a card.
        boolean exact = query.type() != MatchType.NAME && matches.size() == 1;
        return new LookupResponse(query.type(), exact, List.copyOf(matches));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The live client, or the answer for why there is none: {@code 410} for a merged record,
     * {@code 404} for anything else (CP-BR-10, ER-01).
     */
    private Client requireLive(Optional<Client> live, UUID id) {
        return live.orElseGet(() -> {
            clients.findAnyById(id).filter(Client::isMerged).ifPresent(merged -> {
                throw mergedElsewhere(merged);
            });
            throw ClientAccess.notFound();
        });
    }

    /**
     * ER-01 for writes: a caller who may not write this client learns nothing about whether it
     * exists. The denial is still audited, in its own transaction (RB-BR-13).
     */
    private void requireCovering(Client client, CurrentUser caller, String permission) {
        Optional<Scope> scope = access.scopeOf(caller, permission);
        if (scope.isEmpty() || !access.covers(scope.get(), client, caller, permission)) {
            events.recordDenial(client.id(), permission);
            throw ClientAccess.notFound();
        }
    }

    /**
     * §5.3 field-level rules. Past {@link #requireCovering} the caller already knows this client
     * exists, so a {@code 403} here reveals nothing that ER-01 protects.
     */
    private void requireFieldAuthority(Client client, ClientPatch patch, CurrentUser caller) {
        for (Field field : patch.fields()) {
            switch (field.kind()) {
                case ELSEWHERE ->
                    throw deny(
                            client,
                            CLIENT_WRITE,
                            ApiException.forbidden(ErrorCodes.PERMISSION_DENIED, elsewhereMessage(field))
                                    .detail("field", field.json(), "issue", "not editable through PATCH"));
                case RISK -> requireFieldPermission(client, caller, field, CLIENT_KYC);
                case STATUS -> requireFieldPermission(client, caller, field, CLIENT_DELETE);
                case PROFILE, IMMUTABLE -> {
                    // PROFILE rides on client:write, already checked. IMMUTABLE is a business rule
                    // rather than a permission, and is answered with 422 after the version check.
                }
            }
        }
    }

    private void requireFieldPermission(Client client, CurrentUser caller, Field field, String permission) {
        boolean allowed = access.scopeOf(caller, permission)
                .filter(scope -> access.covers(scope, client, caller, permission))
                .isPresent();
        if (!allowed) {
            throw deny(
                    client,
                    permission,
                    ApiException.forbidden(
                                    ErrorCodes.PERMISSION_DENIED,
                                    "Changing " + field.json() + " requires the " + permission + " permission.")
                            .detail("field", field.json(), "permission", permission));
        }
    }

    private static String elsewhereMessage(Field field) {
        return switch (field) {
            case OWNER_MANAGER_ID -> "Change the owner through POST /clients/{id}/reassign (CP-US-05).";
            case TEAM_ID -> "teamId is derived from the owner's primary team and cannot be set (CP-BR-03).";
            case KYC_STATUS -> "Change KYC status through POST /clients/{id}/kyc (CP-BR-04).";
            default -> field.json() + " cannot be changed through PATCH.";
        };
    }

    /** CP-BR-08: the only edit a closed client accepts is being reopened. */
    private static boolean isReopening(ClientPatch patch) {
        return patch.fields().equals(Set.of(Field.STATUS)) && patch.value(Field.STATUS) == ClientStatus.ACTIVE;
    }

    /**
     * {@code 409 VERSION_CONFLICT} carrying the current record in {@code details[0].current}, so
     * the UI can show "your change / current value" instead of discarding what was typed (§4.8).
     * That body discloses decrypted PII on a request that is about to roll back, which is why the
     * audit event commits separately (CP-BR-13).
     */
    private ApiException versionConflict(Client current, CurrentUser caller) {
        events.readSensitiveCommitted(current.id(), "client.version_conflict");
        return ApiException.conflict(
                        ErrorCodes.VERSION_CONFLICT,
                        "This client was changed by someone else. Review the current values and retry.")
                .detail(Map.of("current", assembler.toResponse(current, caller, clock.now())));
    }

    /** Records the denial where it survives the rollback, then hands back the error to throw. */
    private ApiException deny(Client client, String permission, ApiException error) {
        events.recordDenial(client.id(), permission);
        return error;
    }

    private static ApiException outOfScope(String permission) {
        return ApiException.forbidden(
                        ErrorCodes.PERMISSION_DENIED, "This client is outside your " + permission + " scope.")
                .detail("permission", permission);
    }

    /**
     * CP-US-05 and §4.9 both take a reason, and both answer a short one with {@code 422} rather
     * than {@code 400}: the field is present and well-formed, it just does not do its job.
     */
    private static String requireReason(String value, String field) {
        String reason = blankToNull(value);
        if (reason == null || reason.length() < MIN_REASON_LENGTH) {
            throw ApiException.businessRule("A reason of at least " + MIN_REASON_LENGTH + " characters is required.")
                    .detail("field", field, "issue", "must be at least " + MIN_REASON_LENGTH + " characters");
        }
        return reason;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * Shared by every path that resolves a client for a caller, so ER-01 is applied once.
     *
     * @return the break-glass grant that authorized the read, or empty when ordinary role scope did.
     *     The caller needs it to name the grant in the audit context (AT-BR-13).
     */
    private Optional<AccessGrantRepository.Grant> requireReadable(Client client, CurrentUser caller) {
        Optional<Scope> scope = access.scopeOf(caller, CLIENT_READ);
        if (scope.isEmpty()) {
            events.recordDenial(client.id(), CLIENT_READ);
            throw ClientAccess.notFound();
        }
        ClientAccess.Decision decision = access.authorize(scope.get(), client, caller, CLIENT_READ);
        if (!decision.allowed()) {
            events.recordDenial(client.id(), CLIENT_READ);
            throw ClientAccess.notFound();
        }
        return decision.byGrant();
    }

    /**
     * {@code 410} with the survivor's location (CP-BR-10). A chain longer than the hop cap can
     * only be a cycle, and a wrong redirect is worse than an error (CP-EC-09).
     */
    private ApiException mergedElsewhere(Client merged) {
        Client survivor = clients.resolveMergeSurvivor(merged)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorCodes.INTERNAL_ERROR,
                        "Merge chain could not be resolved. Quote the requestId when contacting support."));
        return new ApiException(
                        HttpStatus.GONE, ClientErrorCodes.CLIENT_MERGED, "This client record was merged into another.")
                .header(HttpHeaders.LOCATION, "/api/v1/clients/" + survivor.id())
                .detail("survivorId", survivor.id(), "mergedAt", merged.mergedAt());
    }

    private void requireUnique(Optional<Client> existing, String code, String message, CurrentUser caller) {
        existing.ifPresent(conflict -> {
            ApiException error = ApiException.conflict(code, message);
            // Naming the conflicting record is only safe in scope; out of scope it would leak
            // existence through a create (ER-01). The message alone still tells the caller enough.
            access.scopeOf(caller, CLIENT_READ)
                    .filter(scope -> access.covers(scope, conflict, caller, CLIENT_READ))
                    .ifPresent(scope -> error.detail("conflictingId", conflict.id()));
            throw error;
        });
    }

    /**
     * Auto-detection per §5.3: {@code @} is an email, a {@code CIF-} prefix an external ref, nine
     * or more digits a phone number, anything else a name.
     */
    private Query parse(String email, String phone, String externalRef, String q) {
        List<String> supplied = new ArrayList<>();
        if (notBlank(email)) {
            supplied.add("email");
        }
        if (notBlank(phone)) {
            supplied.add("phone");
        }
        if (notBlank(externalRef)) {
            supplied.add("externalRef");
        }
        if (notBlank(q)) {
            supplied.add("q");
        }
        if (supplied.size() != 1) {
            throw ApiException.validation("q", "supply exactly one of email, phone, externalRef or q");
        }

        if (notBlank(email)) {
            return new Query(MatchType.EMAIL, Emails.normalize(email));
        }
        if (notBlank(phone)) {
            return new Query(MatchType.PHONE, phones.normalize(phone, "phone"));
        }
        if (notBlank(externalRef)) {
            return new Query(MatchType.EXTERNAL_REF, externalRef.trim());
        }

        String term = q.trim();
        if (term.length() < MIN_QUERY_LENGTH) {
            throw ApiException.validation("q", "must be at least " + MIN_QUERY_LENGTH + " characters");
        }
        if (term.contains("@")) {
            return new Query(MatchType.EMAIL, Emails.normalize(term));
        }
        if (term.toUpperCase(Locale.ROOT).startsWith("CIF-")) {
            return new Query(MatchType.EXTERNAL_REF, term);
        }
        if (term.chars().filter(Character::isDigit).count() >= 9) {
            return new Query(MatchType.PHONE, phones.normalize(term, "q"));
        }
        return new Query(MatchType.NAME, term);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** What the server decided the caller was searching for, and the normalized term. */
    private record Query(MatchType type, String value) {}
}
