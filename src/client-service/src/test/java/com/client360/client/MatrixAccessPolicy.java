package com.client360.client;

import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The §9.2.8 permission matrix as an {@link AccessPolicy}, for tests that need more than one role.
 *
 * <p>{@code MvpAccessPolicy} makes every caller a MANAGER at ALL scope (§11.1), which cannot
 * express separation of duties: CP-BR-05 only means something when one caller holds
 * {@code client:kyc} and another does not. This double resolves permission and scope from the
 * token's {@code roles} claim so those rules can be exercised now, a phase before the real
 * implementation arrives with the RBAC tables.
 *
 * <p>It is a test fixture, not a preview of v2. Production reads {@code role_permissions} and
 * {@code user_roles} from the database; nothing in {@code src/main} reads a role string
 * (RB-BR-01). Only the permissions the Client Profile endpoints ask for are modelled.
 */
public class MatrixAccessPolicy implements AccessPolicy {

    private static final Map<String, Map<String, Scope>> MATRIX = Map.of(
            "MANAGER",
            Map.of(
                    Permissions.CLIENT_READ, Scope.OWN,
                    Permissions.CLIENT_WRITE, Scope.OWN,
                    Permissions.PRODUCT_READ, Scope.OWN,
                    Permissions.PRODUCT_SYNC, Scope.OWN,
                    Permissions.INTERACTION_READ, Scope.OWN,
                    Permissions.INTERACTION_WRITE, Scope.OWN),
            "SUPERVISOR",
            Map.of(
                    Permissions.CLIENT_READ, Scope.TEAM,
                    Permissions.CLIENT_WRITE, Scope.TEAM,
                    Permissions.CLIENT_REASSIGN, Scope.TEAM,
                    Permissions.CLIENT_KYC, Scope.TEAM,
                    Permissions.PRODUCT_READ, Scope.TEAM,
                    Permissions.PRODUCT_SYNC, Scope.TEAM,
                    Permissions.INTERACTION_READ, Scope.TEAM,
                    Permissions.INTERACTION_WRITE, Scope.TEAM,
                    Permissions.INTERACTION_DELETE, Scope.TEAM),
            "ADMIN",
            Map.ofEntries(
                    Map.entry(Permissions.CLIENT_READ, Scope.ALL),
                    Map.entry(Permissions.CLIENT_WRITE, Scope.ALL),
                    Map.entry(Permissions.CLIENT_DELETE, Scope.ALL),
                    Map.entry(Permissions.CLIENT_REASSIGN, Scope.ALL),
                    Map.entry(Permissions.CLIENT_MERGE, Scope.ALL),
                    Map.entry(Permissions.CLIENT_KYC, Scope.ALL),
                    Map.entry(Permissions.PRODUCT_READ, Scope.ALL),
                    Map.entry(Permissions.PRODUCT_WRITE, Scope.ALL),
                    Map.entry(Permissions.PRODUCT_SYNC, Scope.ALL),
                    Map.entry(Permissions.INTERACTION_READ, Scope.ALL),
                    Map.entry(Permissions.INTERACTION_WRITE, Scope.ALL),
                    Map.entry(Permissions.INTERACTION_DELETE, Scope.ALL)),
            "AUDITOR",
            Map.of(
                    Permissions.CLIENT_READ, Scope.ALL,
                    Permissions.PRODUCT_READ, Scope.ALL,
                    Permissions.INTERACTION_READ, Scope.ALL),
            "COMPLIANCE",
            Map.of(
                    Permissions.CLIENT_READ, Scope.ALL,
                    Permissions.CLIENT_KYC, Scope.ALL,
                    Permissions.CLIENT_ERASE, Scope.ALL,
                    Permissions.PRODUCT_READ, Scope.ALL,
                    Permissions.INTERACTION_READ, Scope.ALL));

    /** RB-BR-03: several roles granting one permission yield the widest scope among them. */
    @Override
    public Optional<Scope> scopeOf(CurrentUser user, String permission) {
        Scope widest = null;
        for (String role : user.roles()) {
            widest = Scope.widest(widest, MATRIX.getOrDefault(role, Map.of()).get(permission));
        }
        return Optional.ofNullable(widest);
    }

    /** Overrides the MVP stub by type; {@code SecurityConfig}'s bean stays untouched. */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        AccessPolicy matrixAccessPolicy() {
            return new MatrixAccessPolicy();
        }
    }
}
