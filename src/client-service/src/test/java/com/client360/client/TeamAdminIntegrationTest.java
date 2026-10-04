package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Team administration (SPEC.md §9.3, RB-US-06).
 *
 * <p>A team is the unit of the {@code TEAM} data scope (RB-BR-02), so the tests that matter here are
 * the ones about what a write to a team does to who can see whose clients.
 */
class TeamAdminIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Reading {

        /** §9.3: "Includes member counts". */
        @Test
        void listsTeamsWithTheirMemberCounts_9_3() throws Exception {
            mvc.perform(get("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.code == 'RWN')].memberCount").value(3))
                    .andExpect(jsonPath("$[?(@.code == 'RWS')].memberCount").value(2));
        }

        /** A deactivated employee is not a member of anything. */
        @Test
        void countsOnlyActiveMembers_9_3() throws Exception {
            jdbc.sql("UPDATE client.users SET primary_team_id = :team WHERE id = :user")
                    .param("team", TEAM_RWN)
                    .param("user", BARTOSZ_BYLY)
                    .update();
            mvc.perform(get("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(jsonPath("$[?(@.code == 'RWN')].memberCount").value(3));
        }

        /** {@code user:read}, so a supervisor may read the list; a manager holds none. */
        @Test
        void requiresUserRead_9_3() throws Exception {
            mvc.perform(get("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    class Creating {

        @Test
        void createsATeam_9_3() throws Exception {
            create("Retail Kraków", "RKR", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("RKR"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.timezone").value("Europe/Warsaw"));
            assertThat(countEvents("auth.team_created")).isEqualTo(1);
        }

        @Test
        void refusesADuplicateCode_TEAM_DUPLICATE_CODE() throws Exception {
            create("Another North", "RWN", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEAM_DUPLICATE_CODE"));
        }

        /** {@code team:manage} is admin-only in §9.2.8, so a supervisor cannot create a team. */
        @Test
        void requiresTeamManage_9_3() throws Exception {
            mvc.perform(post("/api/v1/teams")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Mine\",\"code\":\"MINE\"}"))
                    .andExpect(status().isForbidden());
        }

        /** §9.3: a team's supervisor must hold the SUPERVISOR role. */
        @Test
        void refusesASupervisorWithoutTheRole_9_3() throws Exception {
            bearerFor(ADAM_NOWAK, "MANAGER");
            create("Retail Kraków", "RKR", ADAM_NOWAK)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("SUPERVISOR_ROLE_REQUIRED"));
        }

        @Test
        void acceptsASupervisorWhoHoldsTheRole_9_3() throws Exception {
            bearerFor(OLA_WISNIEWSKA, "SUPERVISOR");
            create("Retail Kraków", "RKR", OLA_WISNIEWSKA)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.supervisorId").value(OLA_WISNIEWSKA.toString()));
        }
    }

    @Nested
    class Updating {

        @Test
        void patchesWithIfMatch_4_8() throws Exception {
            mvc.perform(patch("/api/v1/teams/{id}", TEAM_RWN)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(versionOf(TEAM_RWN)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Retail Warsaw North & Centre\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Retail Warsaw North & Centre"));
            assertThat(countEvents("auth.team_updated")).isEqualTo(1);
        }

        @Test
        void refusesAStaleVersion_VERSION_CONFLICT() throws Exception {
            mvc.perform(patch("/api/v1/teams/{id}", TEAM_RWN)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .header(HttpHeaders.IF_MATCH, etagOf(versionOf(TEAM_RWN) + 7))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Nope\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        /**
         * §9.3: a parent that would close a cycle. {@code ck_teams_no_self_parent} covers one hop;
         * anything longer needs the walk, and a cycle already in the data must not hang the walk.
         */
        @Test
        void refusesAParentThatWouldCloseACycle_9_3() throws Exception {
            // RWS becomes a child of RWN, then RWN is asked to become a child of RWS.
            patchTeam(TEAM_RWS, "{\"parentTeamId\":\"" + TEAM_RWN + "\"}").andExpect(status().isOk());
            patchTeam(TEAM_RWN, "{\"parentTeamId\":\"" + TEAM_RWS + "\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("TEAM_HIERARCHY_CYCLE"));
        }

        @Test
        void refusesATeamAsItsOwnParent_ck_teams_no_self_parent() throws Exception {
            patchTeam(TEAM_RWN, "{\"parentTeamId\":\"" + TEAM_RWN + "\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("TEAM_HIERARCHY_CYCLE"));
        }

        @Test
        void refusesASupervisorWithoutTheRole_9_3() throws Exception {
            bearerFor(ADAM_NOWAK, "MANAGER");
            patchTeam(TEAM_RWN, "{\"supervisorId\":\"" + ADAM_NOWAK + "\"}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("SUPERVISOR_ROLE_REQUIRED"));
        }

        /**
         * Naming a supervisor widens that person's TEAM scope immediately — which is the point, and
         * the reason this endpoint is admin-only.
         */
        @Test
        void namingASupervisorWidensTheirScope_RB_BR_02() throws Exception {
            String client = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isNotFound());

            patchTeam(TEAM_RWS, "{\"supervisorId\":\"" + OLA_WISNIEWSKA + "\"}").andExpect(status().isOk());
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Members {

        @Test
        void addsAMember_9_3() throws Exception {
            addMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            assertThat(countEvents("auth.team_member_added")).isEqualTo(1);
            mvc.perform(get("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(jsonPath("$[?(@.code == 'RWS')].memberCount").value(3));
        }

        /** A membership is a scope change, so adding one widens what that user can read. */
        @Test
        void addingAMemberWidensTheirTeamScope_RB_BR_02() throws Exception {
            String client = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isNotFound());

            addMember(TEAM_RWS, OLA_WISNIEWSKA).andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/clients/{id}", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk());
        }

        /** RB-EC-06: idempotent, so a retry converges and emits nothing the second time. */
        @Test
        void addingTwiceChangesNothing_RB_EC_06() throws Exception {
            addMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            addMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            assertThat(countEvents("auth.team_member_added")).isEqualTo(1);
        }

        @Test
        void refusesAnInactiveUser_9_3() throws Exception {
            addMember(TEAM_RWS, BARTOSZ_BYLY)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].status").value("DEACTIVATED"));
        }

        /** §9.3: "sets {@code left_at}" — the membership ended, it did not never exist. */
        @Test
        void removingAMemberEndsItRatherThanDeletingIt_9_3() throws Exception {
            addMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            removeMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            assertThat(leftAtIsSet(TEAM_RWS, ADAM_NOWAK)).isTrue();
            assertThat(countEvents("auth.team_member_removed")).isEqualTo(1);
        }

        /**
         * §9.3: refused while the user owns clients in that team. {@code clients.team_id} is derived
         * from the owner's primary team (CP-BR-03), so removing the membership would leave those
         * clients in a team their owner has left.
         */
        @Test
        void refusesWhileTheUserOwnsClientsInThatTeam_9_3() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            addMember(TEAM_RWN, ADAM_NOWAK).andExpect(status().isNoContent());
            removeMember(TEAM_RWN, ADAM_NOWAK)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("OWNED_CLIENTS_IN_TEAM"))
                    .andExpect(jsonPath("$.details[0].count").value(1));
        }

        /** §9.3: a user in no team has no TEAM scope and cannot be assigned a client at all. */
        @Test
        void refusesRemovingTheOnlyMembership_9_3() throws Exception {
            // Sofia has no primary team, so a single team_members row is her only membership.
            addMember(TEAM_RWN, SOFIA_ADAMSKA).andExpect(status().isNoContent());
            removeMember(TEAM_RWN, SOFIA_ADAMSKA)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].blocker").value("LAST_TEAM_MEMBERSHIP"));
        }

        /** And permits it once they belong somewhere else. */
        @Test
        void permitsRemovalWhenAnotherMembershipRemains_9_3() throws Exception {
            addMember(TEAM_RWN, SOFIA_ADAMSKA).andExpect(status().isNoContent());
            addMember(TEAM_RWS, SOFIA_ADAMSKA).andExpect(status().isNoContent());
            removeMember(TEAM_RWN, SOFIA_ADAMSKA).andExpect(status().isNoContent());
        }

        /** Removing a membership that already ended is the state the caller asked for. */
        @Test
        void removingTwiceIsNotAnError_RB_EC_06() throws Exception {
            addMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            removeMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            removeMember(TEAM_RWS, ADAM_NOWAK).andExpect(status().isNoContent());
            assertThat(countEvents("auth.team_member_removed")).isEqualTo(1);
        }

        /** CP-BR-03: one primary team at a time, enforced by {@code ux_tm_one_primary}. */
        @Test
        void refusesASecondPrimaryMembership_CP_BR_03() throws Exception {
            mvc.perform(post("/api/v1/teams/{id}/members", TEAM_RWN)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + ADAM_NOWAK + "\",\"isPrimary\":true}"))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/api/v1/teams/{id}/members", TEAM_RWS)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"" + ADAM_NOWAK + "\",\"isPrimary\":true}"))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions create(String name, String code, UUID supervisorId) throws Exception {
        String supervisor = supervisorId == null ? "" : ",\"supervisorId\":\"" + supervisorId + "\"";
        return mvc.perform(post("/api/v1/teams")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\"" + supervisor + "}"));
    }

    private ResultActions patchTeam(UUID teamId, String body) throws Exception {
        return mvc.perform(patch("/api/v1/teams/{id}", teamId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .header(HttpHeaders.IF_MATCH, etagOf(versionOf(teamId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions addMember(UUID teamId, UUID userId) throws Exception {
        return mvc.perform(post("/api/v1/teams/{id}/members", teamId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}"));
    }

    private ResultActions removeMember(UUID teamId, UUID userId) throws Exception {
        return mvc.perform(delete("/api/v1/teams/{id}/members/{userId}", teamId, userId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")));
    }

    private int versionOf(UUID teamId) {
        return jdbc.sql("SELECT version FROM client.teams WHERE id = :id")
                .param("id", teamId)
                .query(Integer.class)
                .single();
    }

    private boolean leftAtIsSet(UUID teamId, UUID userId) {
        return jdbc.sql("SELECT left_at IS NOT NULL FROM client.team_members"
                        + " WHERE team_id = :team AND user_id = :user")
                .param("team", teamId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    private static String etagOf(int version) {
        return "\"" + version + "\"";
    }
}
