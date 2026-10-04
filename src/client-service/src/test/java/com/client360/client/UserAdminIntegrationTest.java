package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.client.client.WorkloadClient;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

/**
 * User administration (SPEC.md §9.3, RB-BR-06, RB-BR-07, RB-BR-08, RB-BR-14).
 *
 * <p>interaction-service is doubled, because RB-BR-07 counts open tickets and those live there.
 * What that hides is one HTTP hop; what it leaves under test is the part that matters — that a
 * deactivation is refused while work is still assigned, and that every blocker is reported together
 * rather than one failed attempt at a time.
 */
@Import(UserAdminIntegrationTest.Workload.class)
class UserAdminIntegrationTest extends AbstractIntegrationTest {

    /** What the doubled interaction-service reports. A test raises it to block a deactivation. */
    static volatile int openTickets = 0;

    /** Set to make the doubled interaction-service unreachable, as an outage would. */
    static volatile boolean workloadAvailable = true;

    @BeforeEach
    void resetWorkload() {
        openTickets = 0;
        workloadAvailable = true;
    }

    @Nested
    class Reading {

        @Test
        void listsEveryUserForAnAdmin_9_3() throws Exception {
            mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    // The eight employees this fixture seeds (seed_local.sql has a ninth).
                    .andExpect(jsonPath("$.totalElements").value(8))
                    .andExpect(jsonPath("$.content[0].email").exists())
                    // Never the hash, at any scope, for any role.
                    .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());
        }

        /** §9.3: "Supervisors see their team only" — which is what {@code user:read} at TEAM means. */
        @Test
        void aSupervisorSeesTheirTeamOnly_9_3() throws Exception {
            mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    // RWN: Ola, Adam, Marta.
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.content[*].primaryTeamId")
                            .value(Matchers.everyItem(Matchers.is(TEAM_RWN.toString()))));
        }

        @Test
        void filtersByRoleStatusAndQuery_9_3() throws Exception {
            // Granting a role is how a role filter has anything to find.
            bearerFor(ADAM_NOWAK, "MANAGER");
            mvc.perform(get("/api/v1/users?role=MANAGER")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(ADAM_NOWAK.toString()));
            mvc.perform(get("/api/v1/users?status=DEACTIVATED")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(jsonPath("$.totalElements").value(1));
            mvc.perform(get("/api/v1/users?q=nowak")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void rejectsAnUnknownSortField_9_3() throws Exception {
            mvc.perform(get("/api/v1/users?sort=salary")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("sort"));
        }

        /** The ETag is what a later PATCH has to echo (§4.8). */
        @Test
        void readingOneUserCarriesItsVersion_4_8() throws Exception {
            String etag = mvc.perform(get("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(HttpHeaders.ETAG))
                    .andExpect(jsonPath("$.employeeNo").value("EMP-1002"))
                    .andReturn()
                    .getResponse()
                    .getHeader(HttpHeaders.ETAG);
            // Whatever it is, it is the value a PATCH must echo. Not asserted as a literal: the
            // fixture upserts this row before every test and client.users bumps version on UPDATE.
            assertThat(etag).matches("\"\\d+\"");
        }

        /** ER-01's shape, applied to users: out of scope is absent, not forbidden. */
        @Test
        void aUserOutsideTheCallersTeamIsNotFound_9_3() throws Exception {
            mvc.perform(get("/api/v1/users/{id}", JAN_ZIELINSKI)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }

        @Test
        void aManagerHoldsNoUserRead_9_2_8() throws Exception {
            mvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }
    }

    @Nested
    class Creating {

        /** §9.3: the password is never in the request. */
        @Test
        void createsAUserWhoCannotYetSignIn_9_3() throws Exception {
            create("EMP-2001", "n.nowy@bank.example", "Nowy Pracownik")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.mustChangePassword").value(true))
                    .andExpect(jsonPath("$.primaryTeamId").value(TEAM_RWN.toString()));

            // The stored hash is random, so the account exists and nobody can sign into it. That is
            // the correct state for an invitation flow that is not built yet: no access, rather than
            // a default password.
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"n.nowy@bank.example\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void refusesADuplicateEmail_USER_DUPLICATE_EMAIL() throws Exception {
            create("EMP-2001", "a.nowak@bank.example", "Imposter")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("USER_DUPLICATE_EMAIL"));
        }

        @Test
        void refusesADuplicateEmployeeNumber_USER_DUPLICATE_EMPLOYEE_NO() throws Exception {
            create("EMP-1002", "someone.else@bank.example", "Someone Else")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("USER_DUPLICATE_EMPLOYEE_NO"));
        }

        @Test
        void requiresUserWrite_9_3() throws Exception {
            mvc.perform(post("/api/v1/users")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"employeeNo":"EMP-2002","email":"x@bank.example","fullName":"X"}"""))
                    .andExpect(status().isForbidden());
        }

        @Test
        void auditsTheCreation_AT_US_01() throws Exception {
            jdbc.sql("DELETE FROM client.outbox_events").update();
            create("EMP-2001", "n.nowy@bank.example", "Nowy Pracownik").andExpect(status().isCreated());
            assertThat(countEvents("auth.user_created")).isEqualTo(1);
        }
    }

    @Nested
    class Updating {

        @Test
        void patchesWithIfMatch_4_8() throws Exception {
            int before = versionOf(ADAM_NOWAK);
            mvc.perform(patch("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(before))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Adam Nowak-Kowalski\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.fullName").value("Adam Nowak-Kowalski"))
                    .andExpect(jsonPath("$.version").value(before + 1));
        }

        @Test
        void refusesAStaleVersion_VERSION_CONFLICT() throws Exception {
            mvc.perform(patch("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(versionOf(ADAM_NOWAK) + 7))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Someone Else\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        @Test
        void requiresIfMatch_4_8() throws Exception {
            mvc.perform(patch("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Adam\"}"))
                    .andExpect(status().isPreconditionRequired());
        }

        /**
         * RB-EC-09: {@code clients.team_id} is derived from the owner's primary team (CP-BR-03), so
         * moving someone who owns clients would leave their book pointing at a team they have left —
         * visible to the wrong supervisor, invisible to the right one.
         */
        @Test
        void refusesATeamMoveWhileTheyOwnClients_RB_EC_09() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            mvc.perform(patch("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(versionOf(ADAM_NOWAK)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"primaryTeamId\":\"" + TEAM_RWS + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("OWNED_CLIENTS_IN_CURRENT_TEAM"))
                    .andExpect(jsonPath("$.details[0].count").value(1));
        }

        @Test
        void allowsATeamMoveWhenTheyOwnNothing_RB_EC_09() throws Exception {
            mvc.perform(patch("/api/v1/users/{id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(versionOf(ADAM_NOWAK)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"primaryTeamId\":\"" + TEAM_RWS + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.primaryTeamId").value(TEAM_RWS.toString()));
        }
    }

    @Nested
    class Roles {

        @Test
        void grantsARoleWithAReason_RB_BR_14() throws Exception {
            grantRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Covering the branch while Ola is on leave")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(Matchers.hasItem("SUPERVISOR")));
            assertThat(countEvents("auth.role_changed")).isEqualTo(1);
        }

        /** RB-BR-14: the reason is mandatory, and {@code ck_ur_reason} agrees. */
        @Test
        void refusesAShortReason_RB_BR_14() throws Exception {
            grantRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "cover").andExpect(status().isBadRequest());
        }

        /**
         * RB-BR-06. The only users who hold {@code role:assign} are admins, so without this the rule
         * would read "an admin may become anything", which is not a rule.
         */
        @Test
        void aUserCannotGrantThemselvesARole_RB_BR_06() throws Exception {
            grantRole(SOFIA_ADAMSKA, "AUDITOR", "Needed to review the quarterly export")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].reason").value("SELF_ELEVATION_FORBIDDEN"));
        }

        @Test
        void refusesAnUnknownRole_9_3() throws Exception {
            grantRole(MARTA_LEWANDOWSKA, "SUPREME_LEADER", "Trying something that does not exist")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("roleCode"));
        }

        @Test
        void revokesARole_RB_BR_14() throws Exception {
            bearerFor(MARTA_LEWANDOWSKA, "MANAGER");
            grantRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Covering the branch while Ola is on leave")
                    .andExpect(status().isOk());
            revokeRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Ola is back from leave")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").value(Matchers.not(Matchers.hasItem("SUPERVISOR"))));
        }

        /** A user with no role at all can do nothing and cannot be told why by the UI. */
        @Test
        void refusesRemovingTheLastRole_9_3() throws Exception {
            bearerFor(MARTA_LEWANDOWSKA, "MANAGER");
            revokeRole(MARTA_LEWANDOWSKA, "MANAGER", "No longer needs any access")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("LAST_ROLE"));
        }

        /** RB-BR-08: a system nobody can administer is unrecoverable. */
        @Test
        void refusesRemovingTheLastAdmin_RB_BR_08() throws Exception {
            bearerFor(SOFIA_ADAMSKA, "ADMIN");
            bearerFor(SOFIA_ADAMSKA, "AUDITOR"); // so LAST_ROLE is not what refuses it
            revokeRole(SOFIA_ADAMSKA, "ADMIN", "Stepping down from administration")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("LAST_ACTIVE_ADMIN"));
        }

        /** And permits it the moment a second admin exists. */
        @Test
        void permitsRemovingAnAdminWhenAnotherRemains_RB_BR_08() throws Exception {
            bearerFor(SOFIA_ADAMSKA, "ADMIN");
            bearerFor(SOFIA_ADAMSKA, "AUDITOR");
            grantRole(EWA_ZGODNOSC, "ADMIN", "Second administrator for continuity")
                    .andExpect(status().isOk());
            revokeRole(SOFIA_ADAMSKA, "ADMIN", "Stepping down from administration")
                    .andExpect(status().isOk());
        }

        /** RB-EC-06: role endpoints are individually idempotent, so a retry converges. */
        @Test
        void revokingARoleTwiceIsNotAnError_RB_EC_06() throws Exception {
            bearerFor(MARTA_LEWANDOWSKA, "MANAGER");
            grantRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Covering the branch while Ola is on leave");
            revokeRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Ola is back from leave")
                    .andExpect(status().isOk());
            revokeRole(MARTA_LEWANDOWSKA, "SUPERVISOR", "Ola is back from leave")
                    .andExpect(status().isOk());
        }

        /** RB-BR-14 again, from the enforcement side: the grant takes effect on the next request. */
        @Test
        void aGrantedRoleTakesEffectImmediatelyForTheTables_RB_BR_14() throws Exception {
            String client = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            // Marta is in RWN and cannot see an RWS client.
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(MARTA_LEWANDOWSKA, "MANAGER")))
                    .andExpect(status().isNotFound());

            grantRole(MARTA_LEWANDOWSKA, "ADMIN", "Temporary administrator while the team is short")
                    .andExpect(status().isOk());
            // Same token, new row: client-service reads the tables per request, not the claim.
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(MARTA_LEWANDOWSKA, "MANAGER")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Deactivating {

        @Test
        void deactivatesAUserWithNothingAssigned_RB_BR_07() throws Exception {
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                    .andExpect(jsonPath("$.deactivatedAt").exists());
            assertThat(countEvents("auth.user_deactivated")).isEqualTo(1);
        }

        /** RB-BR-07: every blocker, with its count, in one answer. */
        @Test
        void enumeratesEveryBlockerAtOnce_RB_BR_07() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            createClient(ADAM_NOWAK, "CIF-3", "anna3@example.com", "+48511234569");
            openTickets = 4;

            deactivate(ADAM_NOWAK, "Left the bank at the end of the month", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("USER_HAS_OWNED_CLIENTS"))
                    .andExpect(jsonPath("$.details[?(@.blocker == 'OWNED_CLIENTS')].count")
                            .value(2))
                    .andExpect(jsonPath("$.details[?(@.blocker == 'OPEN_TICKETS')].count")
                            .value(4));
        }

        /** The handover and the deactivation are one transaction, so they cannot come apart. */
        @Test
        void reassigningTheBookClearsTheBlocker_RB_BR_07() throws Exception {
            String client = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            deactivate(ADAM_NOWAK, "Left the bank at the end of the month", MARTA_LEWANDOWSKA)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEACTIVATED"));

            // The clients moved, and their team was re-derived from the new owner (CP-BR-03).
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(MARTA_LEWANDOWSKA, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.owner.id").value(MARTA_LEWANDOWSKA.toString()));
        }

        /** CP-BR-03: an owner with no primary team leaves their clients unassignable to a team. */
        @Test
        void refusesAReassignmentTargetWithNoTeam_CP_BR_03() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            deactivate(ADAM_NOWAK, "Left the bank at the end of the month", SOFIA_ADAMSKA)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("reassignClientsTo"));
        }

        /**
         * A team whose only supervisor leaves has nobody to approve break-glass or read its book, so
         * the deactivation waits for a replacement (RB-BR-07).
         */
        @Test
        void refusesTheSoleSupervisorOfATeam_RB_BR_07() throws Exception {
            jdbc.sql("UPDATE client.teams SET supervisor_id = :user WHERE id = :team")
                    .param("user", MARTA_LEWANDOWSKA)
                    .param("team", TEAM_RWN)
                    .update();
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details[?(@.blocker == 'SOLE_TEAM_SUPERVISOR')].count")
                            .value(1));
        }

        /** RB-EC-05, and the UI disabling the button is not what makes it true. */
        @Test
        void theLastAdminCannotBeDeactivated_RB_EC_05() throws Exception {
            bearerFor(SOFIA_ADAMSKA, "ADMIN");
            deactivate(SOFIA_ADAMSKA, "Stepping down and leaving the bank", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("LAST_ACTIVE_ADMIN"));
        }

        /**
         * An unreachable interaction-service refuses the deactivation rather than allowing it. A
         * deactivation that went ahead because the ticket count could not be fetched would strand
         * open tickets with an assignee who no longer exists, and nothing would ever report it.
         */
        @Test
        void refusesWhenTheTicketCountCannotBeObtained_RB_BR_07() throws Exception {
            workloadAvailable = false;
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        }

        @Test
        void aDeactivatedUserHoldsNoPermissions_RB_EC_14() throws Exception {
            String bearer = bearerFor(MARTA_LEWANDOWSKA, "MANAGER");
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isOk());
            // The token is still signed and unexpired; the tables say she is gone.
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                    .andExpect(jsonPath("$.permissions").isEmpty());
        }

        @Test
        void cannotDeactivateTwice_ILLEGAL_STATE_TRANSITION() throws Exception {
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isOk());
            deactivate(MARTA_LEWANDOWSKA, "Left the bank at the end of the month", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        /** §9.3: reactivation restores access and forces a password change, with no clients back. */
        @Test
        void reactivationForcesAPasswordChangeAndReturnsNoClients_9_3() throws Exception {
            String client = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            deactivate(ADAM_NOWAK, "Left the bank at the end of the month", MARTA_LEWANDOWSKA)
                    .andExpect(status().isOk());

            mvc.perform(post("/api/v1/users/{id}/reactivate", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Returned to the branch after a secondment\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.mustChangePassword").value(true));

            // The client stayed with whoever has been working it since.
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isNotFound());
        }

        @Test
        void unlockClearsTheLockout_RB_BR_11() throws Exception {
            jdbc.sql("UPDATE client.users SET failed_login_count = 5, locked_until = now() + INTERVAL '15 minutes'"
                            + " WHERE id = :id")
                    .param("id", ADAM_NOWAK)
                    .update();
            mvc.perform(post("/api/v1/users/{id}/unlock", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.locked").value(false))
                    .andExpect(jsonPath("$.failedLoginCount").value(0));
        }
    }

    // ------------------------------------------------------------------ helpers

    private int versionOf(UUID userId) {
        return jdbc.sql("SELECT version FROM client.users WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private static String etagOf(int version) {
        return "\"" + version + "\"";
    }

    private ResultActions create(String employeeNo, String email, String fullName) throws Exception {
        return mvc.perform(post("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"email\":\"" + email + "\",\"fullName\":\"" + fullName
                        + "\",\"primaryTeamId\":\"" + TEAM_RWN + "\"}"));
    }

    private ResultActions grantRole(UUID userId, String roleCode, String reason) throws Exception {
        return mvc.perform(post("/api/v1/users/{id}/roles", userId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleCode\":\"" + roleCode + "\",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions revokeRole(UUID userId, String roleCode, String reason) throws Exception {
        return mvc.perform(delete("/api/v1/users/{id}/roles/{code}", userId, roleCode)
                .param("reason", reason)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")));
    }

    private ResultActions deactivate(UUID userId, String reason, UUID reassignTo) throws Exception {
        String target = reassignTo == null ? "" : ",\"reassignClientsTo\":\"" + reassignTo + "\"";
        return mvc.perform(post("/api/v1/users/{id}/deactivate", userId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"" + target + "}"));
    }

    /**
     * Stands in for interaction-service's open-work count. Subclassed rather than mocked so the
     * production constructor still has to be satisfiable, and so the unreachable case goes through
     * the same exception translation a real outage would.
     */
    @TestConfiguration
    static class Workload {

        @Bean
        @Primary
        WorkloadClient stubWorkload(RestClient.Builder builder) {
            return new WorkloadClient(builder, "http://localhost:0", java.time.Duration.ofMillis(200)) {
                @Override
                public int openTickets(UUID userId) {
                    if (!workloadAvailable) {
                        return super.openTickets(userId);
                    }
                    return openTickets;
                }
            };
        }
    }
}
