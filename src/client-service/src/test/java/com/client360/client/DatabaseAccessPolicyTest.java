package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.client360.common.security.AccessPolicy;
import com.client360.common.security.CurrentUser;
import com.client360.common.security.Permissions;
import com.client360.common.security.Scope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The real policy against the real matrix (SPEC.md §9.2.8, RB-BR-02, RB-BR-03).
 *
 * <p>Every other test in this repository has authorized through {@code MvpAccessPolicy} or a
 * fixture, both of which answer what the test told them to. This one asks the migration what the
 * matrix actually says — so a transcription error in V9, or a permission the code asks for that no
 * role grants, fails here rather than in production.
 */
class DatabaseAccessPolicyTest extends AbstractIntegrationTest {

    @Autowired
    private AccessPolicy policy;

    @Nested
    @DisplayName("the matrix as seeded")
    class Matrix {

        @Test
        void aManagerReadsOnlyTheirOwn_9_2_8() {
            UUID user = withRoles("MANAGER");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).contains(Scope.OWN);
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_WRITE)).contains(Scope.OWN);
        }

        @Test
        void aSupervisorWorksAcrossTheirTeam_9_2_8() {
            UUID user = withRoles("SUPERVISOR");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).contains(Scope.TEAM);
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_REASSIGN))
                    .contains(Scope.TEAM);
        }

        /** AT-BR-11 and the reason managers have no audit access: they hold the code at no scope. */
        @Test
        void aManagerHoldsNoAuditPermissionAtAll_AT_BR_11() {
            UUID user = withRoles("MANAGER");
            assertThat(policy.scopeOf(caller(user), Permissions.AUDIT_READ)).isEmpty();
            assertThat(policy.scopeOf(caller(user), Permissions.AUDIT_VERIFY)).isEmpty();
        }

        /** §9.2.8: an auditor holds no `:write` of any kind, which is what makes it safe to grant. */
        @Test
        void anAuditorHoldsNoWriteAnywhere_9_2_8() {
            UUID user = withRoles("AUDITOR");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).contains(Scope.ALL);
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_WRITE)).isEmpty();
            assertThat(policy.scopeOf(caller(user), Permissions.INTERACTION_WRITE))
                    .isEmpty();
            assertThat(policy.scopeOf(caller(user), Permissions.TASK_WRITE)).isEmpty();
        }

        /** No role deletes audit data, because the code does not exist to be granted. */
        @Test
        void nobodyCanBeGrantedAuditDelete_AT_BR_01() {
            assertThat(jdbc.sql("SELECT count(*) FROM client.permissions WHERE code LIKE 'audit:delete'")
                            .query(Integer.class)
                            .single())
                    .isZero();
        }

        /** Every code the application asks for is a code the matrix knows. */
        @Test
        void theMatrixCoversEveryPermissionTheCodeUses() {
            List<String> seeded = jdbc.sql("SELECT code FROM client.permissions")
                    .query(String.class)
                    .list();
            assertThat(seeded).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
        }
    }

    @Nested
    @DisplayName("how roles combine (RB-BR-03)")
    class Combining {

        /**
         * The rule stated as a sentence in RB-BR-03 — "a manager who is also an auditor reads all
         * clients and writes only their own" — asserted as behaviour.
         */
        @Test
        void severalRolesTakeTheWidestScope_RB_BR_03() {
            UUID user = withRoles("MANAGER", "AUDITOR");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).contains(Scope.ALL);
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_WRITE)).contains(Scope.OWN);
        }

        @Test
        void aPermissionHeldByNeitherRoleStaysUnheld_RB_BR_03() {
            UUID user = withRoles("MANAGER", "AUDITOR");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_MERGE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("when a grant stops counting")
    class Expiry {

        /**
         * Temporary cover for a holiday expires without anyone having to remember to remove it.
         *
         * <p>Granted in the past as well as expired in it: {@code ck_ur_expiry} refuses a grant
         * that expires before it was made, which is correct and which this test first tripped over.
         */
        @Test
        void anExpiredRoleGrantsNothing() {
            UUID user = seedUser();
            grantAt(user, "ADMIN", "now() - INTERVAL '2 days'", "now() - INTERVAL '1 hour'");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_MERGE)).isEmpty();
        }

        @Test
        void aFutureDatedExpiryStillCounts() {
            UUID user = seedUser();
            grant(user, "ADMIN", "now() + INTERVAL '1 hour'");
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_MERGE)).contains(Scope.ALL);
        }

        /**
         * A deactivated account holds nothing, whatever its roles still say. Checked once here
         * rather than remembered by every endpoint.
         */
        @Test
        void aDeactivatedUserHoldsNothing_RB_BR_07() {
            UUID user = withRoles("ADMIN");
            jdbc.sql("UPDATE client.users SET status = 'DEACTIVATED', deactivated_at = now() WHERE id = :id")
                    .param("id", user)
                    .update();
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).isEmpty();
        }

        @Test
        void aSuspendedUserHoldsNothing() {
            UUID user = withRoles("ADMIN");
            jdbc.sql("UPDATE client.users SET status = 'SUSPENDED' WHERE id = :id")
                    .param("id", user)
                    .update();
            assertThat(policy.scopeOf(caller(user), Permissions.CLIENT_READ)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static CurrentUser caller(UUID id) {
        // The roles claim is deliberately wrong here: nothing in the policy reads it (rule 5), and
        // a test that passed because of it would be testing the token rather than the tables.
        return new CurrentUser(id, "someone@bank.example", "Someone", List.of("NOT_A_REAL_ROLE"));
    }

    private UUID withRoles(String... roles) {
        UUID user = seedUser();
        for (String role : roles) {
            grant(user, role, "NULL");
        }
        return user;
    }

    private UUID seedUser() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO client.users (id, employee_no, email, full_name, password_hash, status)
                        VALUES (:id, :employeeNo, :email, 'Policy Fixture', '{noop}local-dev-only', 'ACTIVE')
                        """)
                .param("id", id)
                .param("employeeNo", "EMP-" + id.toString().substring(0, 8))
                .param("email", id + "@bank.example")
                .update();
        return id;
    }

    private void grant(UUID user, String roleCode, String expiresAtSql) {
        grantAt(user, roleCode, "now()", expiresAtSql);
    }

    private void grantAt(UUID user, String roleCode, String grantedAtSql, String expiresAtSql) {
        jdbc.sql("INSERT INTO client.user_roles (user_id, role_id, granted_by, granted_at, expires_at, reason)"
                        + " SELECT :user, r.id, :user, " + grantedAtSql + ", " + expiresAtSql
                        + ", 'seeded by the policy test'"
                        + "   FROM client.roles r WHERE r.code = :role")
                .param("user", user)
                .param("role", roleCode)
                .update();
    }
}
