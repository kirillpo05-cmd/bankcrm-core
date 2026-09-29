package com.client360.client.security;

import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Scope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real {@link AccessPolicy}: permission and scope resolved from {@code role_permissions} and
 * {@code user_roles} (SPEC.md §9.2, RB-BR-02, RB-BR-03).
 *
 * <p>This is what the seam was for. Every endpoint in every service has asked this interface "does
 * the user hold {@code permission}, and at what scope?" since the first one was written, against
 * {@code MvpAccessPolicy}, which answered MANAGER at ALL for everybody. Replacing that answer is
 * the whole of v2's authorization work on the read side: no controller changes, no query changes,
 * and the scope tests written months earlier start meaning something.
 *
 * <p><strong>Nothing here reads a role string</strong> (rule 5). Roles exist in the tables because
 * that is how permissions are administered, but the question asked and answered is always a
 * permission code and a scope. {@code SUPERVISOR} appears nowhere in this file.
 *
 * <p>RB-BR-03: several roles union their permissions and take the <em>widest</em> scope among them.
 * A manager who is also an auditor reads every client and writes only their own — which falls out
 * of {@code MAX(scope)} rather than needing a rule of its own.
 */
@Component
@Primary
public class DatabaseAccessPolicy implements AccessPolicy {

    private final JdbcClient jdbc;

    public DatabaseAccessPolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return the widest scope the user holds this permission at, or empty when they hold it at no
     *     scope — which is a different answer from "not for this record", and the one that lets
     *     callers tell {@code 403} from {@code 404} (ER-01)
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Scope> scopeOf(CurrentUser user, String permission) {
        return widestScope(user.id(), permission);
    }

    private Optional<Scope> widestScope(UUID userId, String permission) {
        // The enum's declaration order is OWN < TEAM < ALL, so MAX over the text would be wrong
        // ('ALL' < 'OWN' < 'TEAM' alphabetically) while MAX over the enum is exactly RB-BR-03.
        // Expiry is filtered here rather than by a partial index, because now() is not IMMUTABLE.
        return jdbc.sql("""
                        SELECT MAX(rp.scope)::text
                          FROM user_roles ur
                          JOIN role_permissions rp ON rp.role_id = ur.role_id
                          JOIN permissions p ON p.id = rp.permission_id
                          JOIN users u ON u.id = ur.user_id
                         WHERE ur.user_id = :userId
                           AND p.code = :permission
                           AND (ur.expires_at IS NULL OR ur.expires_at > now())
                           -- A suspended or deactivated account holds no permission at any scope.
                           -- Checked here so it is one answer for every endpoint rather than a
                           -- check each of them had to remember. Users are never hard-deleted, so
                           -- status is the whole of it — there is no deleted_at on this table.
                           --
                           -- locked_until is deliberately NOT checked. That lock is brute-force
                           -- protection for the login endpoint, not a revocation: honouring it here
                           -- would let anyone disable a colleague's live session by failing five
                           -- logins on their behalf. Revoking access is what deactivation and
                           -- refresh-token revocation are for.
                           AND u.status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("permission", permission)
                .query(String.class)
                .optional()
                .filter(scope -> scope != null)
                .map(Scope::valueOf);
    }
}
