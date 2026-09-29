package com.client360.client;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * {@code GET /me} and {@code GET /permissions/matrix} (SPEC.md §9.3, RB-US-08).
 *
 * <p>These two endpoints are what lets the SPA contain no role strings at all: one says what this
 * caller may do, the other says what every role may do, and both read the rows the enforcement layer
 * reads. A frontend built on them cannot offer a button the API will refuse.
 */
class MeIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Me {

        /** §9.3: identity, roles, teams, effective permissions with scopes. */
        @Test
        void answersWithPermissionsAndScopes_RB_BR_02() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ADAM_NOWAK.toString()))
                    .andExpect(jsonPath("$.email").value("a.nowak@bank.example"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.roles[0].code").value("MANAGER"))
                    .andExpect(jsonPath("$.roles[0].expiresAt").doesNotExist())
                    .andExpect(jsonPath("$.primaryTeam.name").value("Retail Warsaw North"))
                    .andExpect(jsonPath("$.primaryTeam.timezone").value("Europe/Warsaw"))
                    .andExpect(jsonPath("$.teams[0].id").value(TEAM_RWN.toString()))
                    .andExpect(jsonPath("$.teams[0].isPrimary").value(true))
                    .andExpect(jsonPath("$.permissions[?(@.code == 'client:read')].scope")
                            .value("OWN"))
                    .andExpect(jsonPath("$.permissions[?(@.code == 'client:write')].scope")
                            .value("OWN"))
                    .andExpect(jsonPath("$.activeGrants").isEmpty())
                    .andExpect(jsonPath("$.sessionExpiresAt").exists());
        }

        /** RB-BR-02: a supervisor holds the same codes at a wider scope, which is the whole difference. */
        @Test
        void aSupervisorHoldsTheSameCodesAtTeamScope_RB_BR_02() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[?(@.code == 'client:read')].scope")
                            .value("TEAM"))
                    .andExpect(jsonPath("$.permissions[*].code").value(hasItem("dashboard:team")));
        }

        /** AT-BR-11: no manager holds audit access at any scope, so none may appear here either. */
        @Test
        void aManagerSeesNoAuditPermission_AT_BR_11() throws Exception {
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[*].code").value(not(hasItem("audit:read"))))
                    .andExpect(jsonPath("$.permissions[*].code").value(not(hasItem("audit:export"))));
        }

        /**
         * RB-BR-14, and the reason this endpoint reads the tables rather than the token it was called
         * with: an access token is a 15-minute snapshot, and the SPA builds its navigation from this
         * answer. Answering from the token would leave a user looking at menu items a role change had
         * already taken away.
         */
        @Test
        void readsTheTablesNotTheTokenItWasCalledWith_RB_BR_14() throws Exception {
            String bearer = bearerFor(ADAM_NOWAK, "MANAGER");
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(jsonPath("$.permissions[*].code").value(hasItem("client:read")));

            jdbc.sql("DELETE FROM client.user_roles WHERE user_id = :id")
                    .param("id", ADAM_NOWAK)
                    .update();

            // The token still authenticates — it is signed and unexpired — and now grants nothing.
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").isEmpty())
                    .andExpect(jsonPath("$.permissions").isEmpty());
        }

        /** An expired role grants nothing, and is not shown as held (§9.2.7). */
        @Test
        void omitsAnExpiredRole_9_2_7() throws Exception {
            String bearer = bearerFor(ADAM_NOWAK, "MANAGER");
            jdbc.sql("""
                            UPDATE client.user_roles
                               SET granted_at = now() - INTERVAL '30 days',
                                   expires_at = now() - INTERVAL '1 day'
                             WHERE user_id = :id
                            """).param("id", ADAM_NOWAK).update();
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles").isEmpty())
                    .andExpect(jsonPath("$.permissions").isEmpty());
        }

        @Test
        void requiresAuthentication_9_3() throws Exception {
            mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        }

        /** A token whose subject is nobody: RB-BR-15 says users are never hard-deleted, so refuse. */
        @Test
        void refusesATokenWhoseSubjectHasNoRow_RB_BR_15() throws Exception {
            String bearer =
                    TestJwt.bearerFor(UUID.randomUUID(), "ghost@bank.example", "Ghost", java.util.List.of("MANAGER"));
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Matrix {

        /**
         * RB-US-08. The rows come from {@code role_permissions}, so this endpoint and the
         * authorization decision can never disagree — a matrix kept anywhere else documents what
         * someone once intended.
         */
        @Test
        void isReadFromTheRowsEnforcementUses_RB_US_08() throws Exception {
            mvc.perform(get("/api/v1/permissions/matrix")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                                    "$.roles[?(@.code == 'MANAGER')].permissions[?(@.permission == 'client:read')].scope")
                            .value("OWN"))
                    .andExpect(jsonPath(
                                    "$.roles[?(@.code == 'SUPERVISOR')].permissions[?(@.permission == 'client:read')].scope")
                            .value("TEAM"))
                    .andExpect(jsonPath(
                                    "$.roles[?(@.code == 'AUDITOR')].permissions[?(@.permission == 'audit:read')].scope")
                            .value("ALL"))
                    // §9.2.8 has five system roles.
                    .andExpect(jsonPath("$.roles.length()").value(5));
        }

        /** AT-BR-11 again, from the other side: the code does not exist to be granted. */
        @Test
        void containsNoAuditDeleteAnywhere_AT_BR_11() throws Exception {
            mvc.perform(get("/api/v1/permissions/matrix")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allPermissions").value(not(hasItem("audit:delete"))))
                    .andExpect(jsonPath("$.roles[*].permissions[*].permission").value(everyItem(not("audit:delete"))));
        }

        /** §9.3: {@code user:read}. A manager may not read the matrix. */
        @Test
        void requiresUserRead_9_3() throws Exception {
            mvc.perform(get("/api/v1/permissions/matrix")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }
}
