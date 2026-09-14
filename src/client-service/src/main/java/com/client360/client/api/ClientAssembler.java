package com.client360.client.api;

// Static imports: the permission-code holder is also called Permissions, and
// ClientResponse.Permissions owns that simple name here.
import static com.client360.common.security.Permissions.CLIENT_DELETE;
import static com.client360.common.security.Permissions.CLIENT_MERGE;
import static com.client360.common.security.Permissions.CLIENT_REASSIGN;
import static com.client360.common.security.Permissions.CLIENT_WRITE;

import com.client360.client.api.ClientResponse.Kyc;
import com.client360.client.api.ClientResponse.Permissions;
import com.client360.client.api.ClientResponse.Stats;
import com.client360.client.api.ClientResponse.UserSummary;
import com.client360.client.domain.Client;
import com.client360.client.persistence.UserRepository;
import com.client360.client.support.Masks;
import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Turns a {@link Client} into the wire shapes of SPEC.md §5.3. */
@Component
public class ClientAssembler {

    private final UserRepository users;
    private final Masks masks;
    private final AccessPolicy accessPolicy;

    public ClientAssembler(UserRepository users, Masks masks, AccessPolicy accessPolicy) {
        this.users = users;
        this.masks = masks;
        this.accessPolicy = accessPolicy;
    }

    /**
     * The full card. {@code now} comes from PostgreSQL, not the JVM (CLAUDE.md rule 7), so
     * "expires in 12 days" is computed against the same clock that will decide expiry.
     */
    public ClientResponse toResponse(Client client, CurrentUser caller, Instant now) {
        return new ClientResponse(
                client.id(),
                client.externalRef(),
                client.firstName(),
                client.lastName(),
                client.middleName(),
                client.displayName(),
                client.dateOfBirth(),
                client.email(),
                client.phone(),
                // Masked even on the full card, exactly as §5.3 renders it.
                masks.taxId(client.taxId()),
                client.address(),
                client.preferredChannel(),
                client.segment(),
                client.status(),
                client.risk(),
                new Kyc(
                        client.kycStatus(),
                        client.kycVerifiedAt(),
                        client.kycExpiresAt(),
                        daysUntil(now, client.kycExpiresAt()),
                        client.kycRejectionReason()),
                owner(client.ownerManagerId()),
                client.teamId(),
                new Stats(client.lastInteractionAt(), client.openTaskCount()),
                permissions(client, caller),
                client.version(),
                client.createdAt(),
                client.updatedAt());
    }

    /** A search hit: identity enough to disambiguate, contacts masked (CP-BR-13). */
    public LookupResponse.Match toMatch(Client client, boolean inScope) {
        return new LookupResponse.Match(
                client.id(),
                client.displayName(),
                client.externalRef(),
                client.segment(),
                client.kycStatus(),
                inScope ? masks.email(client.email()) : null,
                inScope ? masks.phone(client.phone()) : null,
                owner(client.ownerManagerId()),
                inScope,
                inScope ? client.lastInteractionAt() : null);
    }

    private UserSummary owner(UUID ownerId) {
        return users.findById(ownerId)
                .map(u -> new UserSummary(u.id(), u.fullName()))
                .orElseGet(() -> new UserSummary(ownerId, null));
    }

    /**
     * What the caller may actually do with this record. {@code canEdit} folds in CP-BR-08: a
     * {@code CLOSED} client takes no field edits, so offering the pencil would be a lie.
     */
    private Permissions permissions(Client client, CurrentUser caller) {
        boolean write = accessPolicy.holds(caller, CLIENT_WRITE);
        return new Permissions(
                write && !client.isClosed(),
                accessPolicy.holds(caller, CLIENT_DELETE),
                accessPolicy.holds(caller, CLIENT_REASSIGN),
                accessPolicy.holds(caller, CLIENT_MERGE));
    }

    private static Long daysUntil(Instant now, Instant expiry) {
        return expiry == null ? null : ChronoUnit.DAYS.between(now, expiry);
    }
}
