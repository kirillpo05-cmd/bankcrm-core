package com.client360.client.service;

import static com.client360.common.security.Permissions.CLIENT_READ;
import static com.client360.common.security.Permissions.CLIENT_WRITE;

import com.client360.client.api.ClientAssembler;
import com.client360.client.api.ClientErrorCodes;
import com.client360.client.api.ClientResponse;
import com.client360.client.api.CreateClientRequest;
import com.client360.client.api.LookupResponse;
import com.client360.client.api.LookupResponse.MatchType;
import com.client360.client.domain.Client;
import com.client360.client.persistence.ClientRepository;
import com.client360.client.persistence.ClientRepository.NewClient;
import com.client360.client.persistence.UserRepository;
import com.client360.client.support.Emails;
import com.client360.client.support.PhoneNumbers;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.id.UuidV7;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Scope;
import com.client360.common.time.DatabaseClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private static final int MIN_QUERY_LENGTH = 3;

    private final ClientRepository clients;
    private final UserRepository users;
    private final ClientAccess access;
    private final ClientEvents events;
    private final ClientAssembler assembler;
    private final PhoneNumbers phones;
    private final DatabaseClock clock;

    public ClientService(
            ClientRepository clients,
            UserRepository users,
            ClientAccess access,
            ClientEvents events,
            ClientAssembler assembler,
            PhoneNumbers phones,
            DatabaseClock clock) {
        this.clients = clients;
        this.users = users;
        this.access = access;
        this.events = events;
        this.assembler = assembler;
        this.phones = phones;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- create

    /**
     * CP-US-03. Joins the transaction {@code IdempotencyService} opened, so the client row, the
     * outbox event and the stored idempotent response commit or roll back together (CLAUDE.md
     * rule 3).
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
        Client client = clients.findById(id).orElseGet(() -> {
            // Not visible as a live client. A merged record is the one case that owes the caller
            // more than a 404: the record still exists, under a different id (CP-BR-10).
            clients.findAnyById(id).filter(Client::isMerged).ifPresent(merged -> {
                throw mergedElsewhere(merged);
            });
            throw ClientAccess.notFound();
        });
        requireReadable(client, caller);
        events.readSensitive(client.id(), "client.card");
        return assembler.toResponse(client, caller, clock.now());
    }

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

        List<LookupResponse.Match> matches = new ArrayList<>();
        boolean disclosedOutOfScope = false;
        for (Client client : found) {
            boolean inScope = access.covers(scope, client, caller);
            if (!inScope) {
                // The narrow ER-01 exception covers exact identifier hits only. A name search must
                // not become a way to enumerate other teams' clients.
                if (query.type() == MatchType.NAME) {
                    continue;
                }
                disclosedOutOfScope = true;
            }
            matches.add(assembler.toMatch(client, inScope));
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

    /** Shared by every path that resolves a client for a caller, so ER-01 is applied once. */
    private void requireReadable(Client client, CurrentUser caller) {
        Optional<Scope> scope = access.scopeOf(caller, CLIENT_READ);
        if (scope.isEmpty() || !access.covers(scope.get(), client, caller)) {
            events.recordDenial(client.id(), CLIENT_READ);
            throw ClientAccess.notFound();
        }
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
                    .filter(scope -> access.covers(scope, conflict, caller))
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
