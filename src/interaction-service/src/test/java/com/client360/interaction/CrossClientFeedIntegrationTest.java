package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code GET /interactions} — the cross-client coaching feed (SPEC.md §6.3, IL-US-07), and the V6
 * denormalization that makes it answerable at all.
 *
 * <p>The doubled client-service reports every client as owned by {@code ADAM_NOWAK} on
 * {@code TEAM_RWN}, so a row written through the API carries that ownership without anything else
 * having to happen.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class CrossClientFeedIntegrationTest extends AbstractInteractionIntegrationTest {

    @Nested
    class Stamping {

        /** V6: the write already asked who owns the client, so the row is scoped from that answer. */
        @Test
        void aNewInteractionCarriesTheClientsOwnerAndTeam() throws Exception {
            String id = log("CALL", "Mortgage rate question", "TEAM");
            assertThat(scopeOf(id)).containsExactly(ADAM_NOWAK, TEAM_RWN);
        }

        /** A correction lands scoped the same way, or it would drop out of the feed it belongs in. */
        @Test
        void aCorrectionIsScopedToo_IL_BR_03() throws Exception {
            String original = log("CALL", "Mortgage rate question", "TEAM");
            String correction = jsonField(
                    mvc.perform(post("/api/v1/interactions/{id}/corrections", original)
                                    .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                                    .header(
                                            IdempotencyService.HEADER,
                                            UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"subject":"Mortgage rate, corrected","body":"5.1%, not 4.1%."}"""))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "id");
            assertThat(scopeOf(correction)).containsExactly(ADAM_NOWAK, TEAM_RWN);
        }
    }

    @Nested
    class Scoping {

        @Test
        void aSupervisorSeesTheirOwnTeam_IL_US_07() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Mortgage rate question"));
        }

        /** Naming another team is not a narrower request, it is a different one. */
        @Test
        void aSupervisorCannotAskForAnotherTeam() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "&teamId=" + UUID.randomUUID())
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        /**
         * §6.3: the feed needs TEAM or ALL. A manager's own clients are their timelines, one at a
         * time — a cross-client feed would add nothing but the same rows in one list.
         */
        @Test
        void aManagerAtOwnScopeIsRefused_IL_US_07() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            feed(ADAM_NOWAK, "MANAGER", "")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void anAdminSeesEveryTeam() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            feed(OLA_WISNIEWSKA, "ADMIN", "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        /**
         * IL-BR-09, stricter here than on a timeline: a private note is excluded for everyone in
         * this feed, its own author and an admin included. A coaching view is not where they are
         * read.
         */
        @Test
        void privateNotesAreExcludedForEveryone_IL_BR_09() throws Exception {
            log("NOTE", "A private thought", "PRIVATE");
            log("CALL", "Mortgage rate question", "TEAM");

            feed(OLA_WISNIEWSKA, "ADMIN", "")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Mortgage rate question"));
            // Even the author, who can see it perfectly well on the client's own timeline.
            feed(ADAM_NOWAK, "ADMIN", "")
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        /**
         * RB-BR-02's {@code TEAM} is a set, not a team. A supervisor covering two branches supervises
         * both, and until client-service gained {@code team_members} that case could not even be
         * recorded — this service resolved the scope to one {@code primary_team_id} and showed such a
         * supervisor half their own book. A feed missing rows looks exactly like a quiet week, which
         * is why this is a test and not a comment.
         */
        @Test
        void aSupervisorCoveringTwoBranchesSeesBoth_RB_BR_02() throws Exception {
            String rwn = log("CALL", "Mortgage rate question", "TEAM");
            String rws = log("CALL", "A call from the other branch", "TEAM");
            moveToOtherTeam(rws);

            scopeTeams = java.util.Set.of(TEAM_RWN, TEAM_RWS);
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2));

            // And one branch alone still means one branch.
            scopeTeams = java.util.Set.of(TEAM_RWN);
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Mortgage rate question"));
            assertThat(rwn).isNotEqualTo(rws);
        }

        /** Naming one of their own teams narrows the feed; it is the only narrowing on offer. */
        @Test
        void aNamedTeamOfTheirOwnNarrowsTheFeed_RB_BR_02() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            moveToOtherTeam(log("CALL", "A call from the other branch", "TEAM"));
            scopeTeams = java.util.Set.of(TEAM_RWN, TEAM_RWS);

            feed(OLA_WISNIEWSKA, "SUPERVISOR", "&teamId=" + TEAM_RWS)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("A call from the other branch"));
        }

        /**
         * A scope that could not be resolved is refused, never treated as unfiltered. client-service
         * being unreachable must not be the one condition under which a supervisor sees every team in
         * the bank.
         */
        @Test
        void anUnresolvableScopeIsRefusedRatherThanWidened_RB_BR_02() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            scopeTeams = java.util.Set.of();
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void filtersByTypeAndAuthor() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            log("NOTE", "A note", "TEAM");
            feed(OLA_WISNIEWSKA, "ADMIN", "&type=NOTE")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("A note"));
            feed(OLA_WISNIEWSKA, "ADMIN", "&authorId=" + MARTA_LEWANDOWSKA)
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    /**
     * CP-US-05 through the consumer: a client changes hands, and every interaction already recorded
     * follows. Without this the old team would keep seeing them, which is a disclosure rather than
     * a stale number.
     */
    @Nested
    class Reassignment {

        @Test
        void movingAClientRescopesItsHistory_CP_US_05() throws Exception {
            String id = log("CALL", "Mortgage rate question", "TEAM");
            assertThat(scopeOf(id)).containsExactly(ADAM_NOWAK, TEAM_RWN);

            UUID newTeam = TEAM_RWS;
            int updated = rescope(CLIENT_ID, MARTA_LEWANDOWSKA, newTeam);

            assertThat(updated).isEqualTo(1);
            assertThat(scopeOf(id)).containsExactly(MARTA_LEWANDOWSKA, newTeam);
            // The old team's feed no longer shows it.
            feed(OLA_WISNIEWSKA, "SUPERVISOR", "")
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        /** Writing the stated values rather than a delta is what makes redelivery harmless. */
        @Test
        void applyingTheSameReassignmentTwiceChangesNothing() throws Exception {
            log("CALL", "Mortgage rate question", "TEAM");
            UUID newTeam = TEAM_RWS;

            assertThat(rescope(CLIENT_ID, MARTA_LEWANDOWSKA, newTeam)).isEqualTo(1);
            assertThat(rescope(CLIENT_ID, MARTA_LEWANDOWSKA, newTeam)).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Exercises the repository the consumer calls; the Kafka hop itself is Spring's to deliver. */
    private int rescope(UUID clientId, UUID owner, UUID team) {
        return jdbc.sql("UPDATE interaction.interactions SET client_owner_id = :owner, client_team_id = :team"
                        + " WHERE client_id = :clientId"
                        + "   AND (client_owner_id IS DISTINCT FROM :owner OR client_team_id IS DISTINCT FROM :team)")
                .param("clientId", clientId)
                .param("owner", owner)
                .param("team", team)
                .update();
    }

    private java.util.List<UUID> scopeOf(String id) {
        return jdbc.sql("SELECT client_owner_id, client_team_id FROM interaction.interactions"
                        + " WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query((rs, n) -> java.util.List.of(
                        rs.getObject("client_owner_id", UUID.class), rs.getObject("client_team_id", UUID.class)))
                .single();
    }

    /** Puts one already-logged interaction in the other branch, as a reassignment would. */
    private void moveToOtherTeam(String interactionId) {
        jdbc.sql("UPDATE interaction.interactions SET client_team_id = :team WHERE id = CAST(:id AS uuid)")
                .param("team", TEAM_RWS)
                .param("id", interactionId)
                .update();
    }

    private ResultActions feed(UUID actor, String role, String extraQuery) throws Exception {
        return mvc.perform(
                get("/api/v1/interactions?_=1" + extraQuery).header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role)));
    }

    private String log(String type, String subject, String visibility) throws Exception {
        String direction = "NOTE".equals(type) ? "INTERNAL" : "OUTBOUND";
        String json = """
                {"type":"%s","direction":"%s","subject":"%s","body":"Details.",
                 "occurredAt":"2026-09-07T09:00:00Z","visibility":"%s"}""".formatted(type, direction, subject, visibility);
        return jsonField(
                mvc.perform(post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                                .header(
                                        IdempotencyService.HEADER,
                                        UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
