package com.client360.client;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * RB-BR-02's {@code TEAM} scope, now that {@code team_members} exists (V8).
 *
 * <p>Before it, {@code TEAM} could only mean {@code users.primary_team_id} — the one membership the
 * MVP schema recorded — and a supervisor covering two branches silently lost half their book. The
 * rule always said "teams where the user is supervisor or an active member"; these are the cases
 * that were unreachable until there was a table to record them in.
 */
class TeamScopeIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Membership {

        /** The primary team, which is the case that worked before and must keep working. */
        @Test
        void coversThePrimaryTeam_RB_BR_02() throws Exception {
            String client = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            read(client, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isOk());
        }

        /** A second, additional membership — the reason the table was added. */
        @Test
        void coversAnAdditionalTeamMembership_RB_BR_02() throws Exception {
            String rws = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            // Ola supervises RWN; RWS is not hers until someone says it is.
            read(rws, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isNotFound());

            join(TEAM_RWS, OLA_WISNIEWSKA, false);
            read(rws, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isOk());
        }

        /**
         * A membership that ended grants nothing. Without {@code left_at IS NULL} a transfer would
         * leave a manager reading their old team indefinitely — and nobody would notice, because
         * nothing about the UI would change.
         */
        @Test
        void aLeftMembershipGrantsNothing_RB_BR_02() throws Exception {
            String rws = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            join(TEAM_RWS, OLA_WISNIEWSKA, false);
            read(rws, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isOk());

            jdbc.sql("UPDATE client.team_members SET left_at = now() WHERE team_id = :team AND user_id = :user")
                    .param("team", TEAM_RWS)
                    .param("user", OLA_WISNIEWSKA)
                    .update();
            read(rws, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isNotFound());
        }

        /**
         * Supervising a team is enough on its own. A supervisor is not usually listed as a member of
         * the team they run, and requiring the row would have denied them their own book.
         */
        @Test
        void supervisingATeamIsEnoughWithoutAMembershipRow_RB_BR_02() throws Exception {
            String rws = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            read(rws, EWA_ZGODNOSC, "SUPERVISOR").andExpect(status().isNotFound());

            jdbc.sql("UPDATE client.teams SET supervisor_id = :user WHERE id = :team")
                    .param("user", EWA_ZGODNOSC)
                    .param("team", TEAM_RWS)
                    .update();
            read(rws, EWA_ZGODNOSC, "SUPERVISOR").andExpect(status().isOk());
        }

        /** ER-01 throughout: a client outside the scope is absent, never forbidden. */
        @Test
        void outOfScopeStaysIndistinguishableFromAbsent_ER_01() throws Exception {
            String rws = createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");
            read(rws, OLA_WISNIEWSKA, "SUPERVISOR")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /**
         * The scoped list filters on the same set, so it cannot disagree with what the same caller
         * can open. It used to filter on {@code primary_team_id} alone, which showed a supervisor
         * covering two branches half their own book — and a list missing rows looks exactly like a
         * quiet week.
         */
        @Test
        void theScopedListCoversEveryTeamInScope_RB_BR_02() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            createClient(JAN_ZIELINSKI, "CIF-2", "jan@example.com", "+48511234568");

            mvc.perform(get("/api/v1/clients")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            join(TEAM_RWS, OLA_WISNIEWSKA, false);
            mvc.perform(get("/api/v1/clients")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        /** {@code /me} reports the same set the decision uses, so the screen cannot contradict the API. */
        @Test
        void meReportsTheTeamsInScope_9_3() throws Exception {
            join(TEAM_RWS, OLA_WISNIEWSKA, false);
            mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.teams.length()").value(2))
                    .andExpect(jsonPath("$.teams[?(@.id == '" + TEAM_RWN + "')].isPrimary")
                            .value(true))
                    .andExpect(jsonPath("$.teams[?(@.id == '" + TEAM_RWS + "')].isPrimary")
                            .value(false));
        }
    }

    private void join(UUID teamId, UUID userId, boolean primary) {
        jdbc.sql("""
                        INSERT INTO client.team_members (team_id, user_id, is_primary)
                        VALUES (:team, :user, :primary)
                        ON CONFLICT (team_id, user_id) DO NOTHING
                        """)
                .param("team", teamId)
                .param("user", userId)
                .param("primary", primary)
                .update();
    }

    private org.springframework.test.web.servlet.ResultActions read(String clientId, UUID actor, String role)
            throws Exception {
        return mvc.perform(
                get("/api/v1/clients/{id}", clientId).header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role)));
    }
}
