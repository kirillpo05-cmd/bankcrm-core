package com.client360.interaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * {@code GET /tickets} — the queue (SPEC.md §6.3, IL-US-05).
 *
 * <p>Ordering is by deadline, so the fixtures below are raised with priorities whose SLAs fall in a
 * known order from the same {@code occurredAt}: CRITICAL is four business hours, HIGH twenty-four,
 * LOW a hundred and sixty-eight.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class TicketQueueIntegrationTest extends AbstractInteractionIntegrationTest {

    private static final String OCCURRED_AT = "2026-09-07T09:00:00Z";

    @Nested
    class Ordering {

        /** The queue answers "what is due next", so the soonest deadline is the first row. */
        @Test
        void soonestDeadlineFirst_IL_US_05() throws Exception {
            raise("LOW", "Statement request", ADAM_NOWAK);
            raise("CRITICAL", "Card declined abroad", ADAM_NOWAK);
            raise("HIGH", "Transfer stuck", ADAM_NOWAK);

            queue("")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(3))
                    .andExpect(jsonPath("$.content[0].subject").value("Card declined abroad"))
                    .andExpect(jsonPath("$.content[1].subject").value("Transfer stuck"))
                    .andExpect(jsonPath("$.content[2].subject").value("Statement request"));
        }

        /** A queue row crosses clients, so it has to say whose ticket it is. */
        @Test
        void rowsNameTheirClientAndCarryTheTicketBlock() throws Exception {
            raise("HIGH", "Transfer stuck", ADAM_NOWAK);
            queue("")
                    .andExpect(jsonPath("$.content[0].clientId").value(CLIENT_ID.toString()))
                    .andExpect(jsonPath("$.content[0].ticket.priority").value("HIGH"))
                    .andExpect(jsonPath("$.content[0].ticket.status").value("NEW"))
                    .andExpect(jsonPath("$.content[0].ticket.assignee.fullName").value("Adam Nowak"));
        }

        /** §4.5: the cursor is built from the deadline, because that is the sort key. */
        @Test
        void pagesByDeadline() throws Exception {
            raise("CRITICAL", "First", ADAM_NOWAK);
            raise("HIGH", "Second", ADAM_NOWAK);

            String cursor = com.jayway.jsonpath.JsonPath.read(
                    queue("&limit=1")
                            .andExpect(jsonPath("$.hasMore").value(true))
                            .andExpect(jsonPath("$.content[0].subject").value("First"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.nextCursor");

            queue("&cursor=" + cursor)
                    .andExpect(jsonPath("$.content[0].subject").value("Second"))
                    .andExpect(jsonPath("$.hasMore").value(false));
        }
    }

    @Nested
    class Filtering {

        /** Without an explicit status the queue is open work; closed tickets are history. */
        @Test
        void closedTicketsLeaveTheQueue() throws Exception {
            String id = raise("HIGH", "Transfer stuck", ADAM_NOWAK);
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            move(id, """
                    {"status":"RESOLVED","resolutionNote":"Transfer released."}""");
            move(id, """
                    {"status":"CLOSED"}""");

            queue("").andExpect(jsonPath("$.content.length()").value(0));
            queue("&status=CLOSED").andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        void filtersByPriority() throws Exception {
            raise("LOW", "Statement request", ADAM_NOWAK);
            raise("CRITICAL", "Card declined abroad", ADAM_NOWAK);
            queue("&priority=CRITICAL")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Card declined abroad"));
        }

        /**
         * IL-BR-08: a paused clock is never breached, and the SQL filter has to agree with
         * {@code Ticket.isBreached} or the supervisor's view and the row badge would disagree.
         */
        @Test
        void aPausedTicketIsNotBreached_IL_BR_08() throws Exception {
            String breached = raise("CRITICAL", "Card declined abroad", ADAM_NOWAK);
            String paused = raise("CRITICAL", "Transfer stuck", ADAM_NOWAK);
            move(paused, """
                    {"status":"IN_PROGRESS"}""");
            move(paused, """
                    {"status":"WAITING_CLIENT"}""");

            // Both deadlines are long past — only the running clock counts as missed.
            queue("&slaBreached=true")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(breached));
        }
    }

    /**
     * IL-EC-10, the counterpart to the pause. Waiting on a client is legitimate and stops the SLA;
     * waiting three weeks is not, and no breach filter would ever surface it — a paused clock must
     * not become a hiding place.
     */
    @Nested
    class Staleness {

        @Test
        void aFreshPauseIsNotStale_IL_EC_10() throws Exception {
            String id = raise("HIGH", "Transfer stuck", ADAM_NOWAK);
            park(id);
            queue("").andExpect(jsonPath("$.content[0].ticket.stale").value(false));
            queue("&stale=true").andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        void aPauseOlderThanAFortnightIsFlagged_IL_EC_10() throws Exception {
            String id = raise("HIGH", "Transfer stuck", ADAM_NOWAK);
            park(id);
            backdateWaitingSince(id, 15);

            queue("").andExpect(jsonPath("$.content[0].ticket.stale").value(true));
            queue("&stale=true")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(id));
        }

        /** The boundary is the rule, so it is worth pinning: thirteen days is still just waiting. */
        @Test
        void thirteenDaysIsStillJustWaiting_IL_EC_10() throws Exception {
            String id = raise("HIGH", "Transfer stuck", ADAM_NOWAK);
            park(id);
            backdateWaitingSince(id, 13);
            queue("&stale=true").andExpect(jsonPath("$.content.length()").value(0));
        }

        /** Only a paused ticket can be stale; a running one is judged by its deadline instead. */
        @Test
        void aRunningTicketIsNeverStale_IL_EC_10() throws Exception {
            raise("CRITICAL", "Card declined abroad", ADAM_NOWAK);
            queue("").andExpect(jsonPath("$.content[0].ticket.stale").value(false));
            queue("&stale=true").andExpect(jsonPath("$.content.length()").value(0));
        }

        private void park(String id) throws Exception {
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            move(id, """
                    {"status":"WAITING_CLIENT"}""");
        }
    }

    @Nested
    class Scope {

        /** The default queue is the caller's own desk, which needs no client scope to authorize. */
        @Test
        void defaultsToTheCallersOwnDesk() throws Exception {
            raise("HIGH", "Mine", ADAM_NOWAK);
            raise("HIGH", "Someone else's", MARTA_LEWANDOWSKA);

            queue("")
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Mine"));
        }

        /** A manager at OWN scope has no way to be narrowed to a colleague's desk, so it is no. */
        @Test
        void anotherUsersQueueIsRefusedAtOwnScope() throws Exception {
            raise("HIGH", "Someone else's", MARTA_LEWANDOWSKA);
            mvc.perform(get("/api/v1/tickets?assigneeId={id}", MARTA_LEWANDOWSKA)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        /**
         * V6: a supervisor's queue is every ticket on a client their team owns — the view that was
         * unanswerable until the owning team was denormalized onto the row.
         */
        @Test
        void aSupervisorSeesTheWholeTeamsQueue_IL_US_05() throws Exception {
            raise("HIGH", "Adam's", ADAM_NOWAK);
            raise("HIGH", "Marta's", MARTA_LEWANDOWSKA);

            mvc.perform(get("/api/v1/tickets")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    // Both, because both sit on a client the team owns — not just their own desk.
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        /** Scoped to the team's clients, so a ticket on someone else's client is not in it. */
        @Test
        void aSupervisorsQueueStopsAtTheirTeamsClients_IL_US_05() throws Exception {
            raise("HIGH", "Ours", ADAM_NOWAK);
            jdbc.sql("UPDATE interaction.interactions SET client_team_id = :other")
                    .param("other", TEAM_RWS)
                    .update();

            mvc.perform(get("/api/v1/tickets")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        /**
         * And it stops there only because that team is outside their scope. The same ticket is in the
         * queue of a supervisor who covers both branches (RB-BR-02) — the queue follows the scope, not
         * a single primary team.
         */
        @Test
        void aSupervisorCoveringBothBranchesSeesBoth_RB_BR_02() throws Exception {
            raise("HIGH", "Ours", ADAM_NOWAK);
            jdbc.sql("UPDATE interaction.interactions SET client_team_id = :other")
                    .param("other", TEAM_RWS)
                    .update();
            scopeTeams = java.util.Set.of(TEAM_RWN, TEAM_RWS);

            mvc.perform(get("/api/v1/tickets")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        /** An unresolvable scope is refused, never quietly widened to every team in the bank. */
        @Test
        void anUnresolvableScopeIsRefused_RB_BR_02() throws Exception {
            raise("HIGH", "Ours", ADAM_NOWAK);
            scopeTeams = java.util.Set.of();

            mvc.perform(get("/api/v1/tickets")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void anAdminMayReadAnotherUsersQueue() throws Exception {
            raise("HIGH", "Someone else's", MARTA_LEWANDOWSKA);
            mvc.perform(get("/api/v1/tickets?assigneeId={id}", MARTA_LEWANDOWSKA)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        /**
         * One client's queue is authorized per client, which is what a supervisor can use today.
         * ER-01 shapes the refusal: out of scope is the same 404 as absent.
         */
        @Test
        void aClientQueueIsAuthorizedPerClient_ER_01() throws Exception {
            raise("HIGH", "Someone else's", MARTA_LEWANDOWSKA);
            mvc.perform(get("/api/v1/tickets?clientId={id}", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1));

            clientInScope = false;
            mvc.perform(get("/api/v1/tickets?clientId={id}", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound());
        }

        /** Notes and calls are not tickets, and a worklist must not fill up with them. */
        @Test
        void onlyTicketsAppear() throws Exception {
            mvc.perform(create("""
                            {"type":"NOTE","direction":"INTERNAL","subject":"A note","body":"Text.",
                             "occurredAt":"%s"}""".formatted(OCCURRED_AT))).andExpect(status().isCreated());
            queue("").andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions queue(String extraQuery) throws Exception {
        return mvc.perform(get("/api/v1/tickets?_=1" + extraQuery)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                .andExpect(status().isOk());
    }

    private String raise(String priority, String subject, UUID assigneeId) throws Exception {
        String json = """
                {"type":"TICKET","direction":"INTERNAL","subject":"%s","body":"Details.",
                 "occurredAt":"%s","ticket":{"priority":"%s","assigneeId":"%s"}}""".formatted(subject, OCCURRED_AT, priority, assigneeId);
        return jsonField(
                mvc.perform(create(json))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private ResultActions move(String id, String json) throws Exception {
        return mvc.perform(patch("/api/v1/interactions/{id}/ticket", id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.RequestBuilder create(String json) {
        return post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /** Simulates a pause that really has lasted, without making the test wait a fortnight. */
    private void backdateWaitingSince(String id, int days) {
        jdbc.sql("UPDATE interaction.interactions SET waiting_since = now() - make_interval(days => :days)"
                        + " WHERE id = CAST(:id AS uuid)")
                .param("days", days)
                .param("id", id)
                .update();
    }

    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
