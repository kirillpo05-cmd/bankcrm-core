package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code PATCH /interactions/{id}/ticket} — the lifecycle of IL-BR-08 and what it does to the SLA
 * of IL-BR-07.
 *
 * <p>{@code ADAM_NOWAK} is the client's owner in the doubled client-service, so he may act on the
 * ticket; {@code MARTA_LEWANDOWSKA} is neither owner nor assignee and holds {@code ticket:write}
 * only at {@code OWN}, which is what makes the §6.3 permission rule testable.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class TicketTransitionIntegrationTest extends AbstractInteractionIntegrationTest {

    /** Monday 7 September 2026, 11:00 Warsaw. CRITICAL from here is 13:00 UTC the same day. */
    private static final String OCCURRED_AT = "2026-09-07T09:00:00Z";

    @Nested
    class Transitions {

        @Test
        void newMovesToInProgress_IL_BR_08() throws Exception {
            String id = raise("MEDIUM");
            patchTicket(id, ADAM_NOWAK, """
                    {"status":"IN_PROGRESS"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ticket.status").value("IN_PROGRESS"));
        }

        /** The refusal names the legal moves, so a UI never has to keep its own copy of the table. */
        @Test
        void anIllegalMoveIsRejectedAndNamesTheAlternatives_IL_BR_08() throws Exception {
            String id = raise("MEDIUM");
            patchTicket(id, ADAM_NOWAK, """
                    {"status":"RESOLVED","resolutionNote":"Fixed it."}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                    .andExpect(jsonPath("$.details[0].from").value("NEW"))
                    .andExpect(jsonPath("$.details[0].legalTargets.length()").value(2));
        }

        @Test
        void closedIsTerminal_IL_BR_08() throws Exception {
            String id = raise("MEDIUM");
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            move(id, """
                    {"status":"RESOLVED","resolutionNote":"Card re-issued."}""");
            move(id, """
                    {"status":"CLOSED"}""");
            patchTicket(id, ADAM_NOWAK, """
                    {"status":"IN_PROGRESS"}""")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details[0].legalTargets.length()").value(0));
        }

        /** ck_interactions_ticket_resolution, refused at the edge so the field is named. */
        @Test
        void resolvingWithoutANoteIsRejected() throws Exception {
            String id = raise("MEDIUM");
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            patchTicket(id, ADAM_NOWAK, """
                    {"status":"RESOLVED"}""")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("resolutionNote"));
        }

        /**
         * ck_interactions_ticket_resolved_at ties the timestamp to the status, so reopening has to
         * clear it — a ticket that is open again has no resolution date.
         */
        @Test
        void reopeningClearsTheResolutionTimestamp() throws Exception {
            String id = raise("MEDIUM");
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            move(id, """
                    {"status":"RESOLVED","resolutionNote":"Card re-issued."}""").andExpect(jsonPath("$.ticket.resolvedAt").isNotEmpty());
            move(id, """
                    {"status":"IN_PROGRESS"}""").andExpect(jsonPath("$.ticket.resolvedAt").isEmpty());
        }
    }

    @Nested
    class Sla {

        /**
         * IL-EC-09: raising priority recomputes from the original {@code occurredAt}, so a ticket
         * raised long ago and escalated now is breached on the spot. That is the point — escalation
         * reveals lateness rather than resetting the clock.
         */
        @Test
        void raisingPriorityRecomputesFromOccurredAt_IL_EC_09() throws Exception {
            String id = raise("LOW");
            patchTicket(id, OLA_WISNIEWSKA, "SUPERVISOR", """
                    {"priority":"CRITICAL"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ticket.priority").value("CRITICAL"))
                    // Monday 11:00 Warsaw + 4 business hours, not "four hours from now".
                    .andExpect(jsonPath("$.ticket.slaDueAt").value("2026-09-07T13:00:00.000Z"))
                    .andExpect(jsonPath("$.ticket.slaBreached").value(true));
        }

        /** Lowering must not buy time that was never granted, so the deadline stays put. */
        @Test
        void loweringPriorityLeavesTheDeadlineAlone_IL_BR_07() throws Exception {
            String id = raise("HIGH");
            String before = slaDueAt(id);
            move(id, """
                    {"priority":"LOW"}""").andExpect(jsonPath("$.ticket.priority").value("LOW"));
            assertThat(slaDueAt(id)).isEqualTo(before);
        }

        /** §6.3: escalating to CRITICAL is a supervisor's call, asked as permission + scope. */
        @Test
        void aManagerCannotEscalateToCritical() throws Exception {
            String id = raise("LOW");
            patchTicket(id, ADAM_NOWAK, """
                    {"priority":"CRITICAL"}""")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("priority"));
        }

        /**
         * IL-BR-08: the pause gives back exactly the business hours it cost, and the deadline moves
         * by the same amount it records. Asserting the invariant rather than a literal duration
         * keeps the test independent of when it runs — the pause is seeded far enough back that
         * business hours certainly elapsed.
         */
        @Test
        void leavingWaitingClientShiftsTheDeadlineByWhatThePauseCost_IL_BR_08() throws Exception {
            String id = raise("LOW");
            move(id, """
                    {"status":"IN_PROGRESS"}""");
            move(id, """
                    {"status":"WAITING_CLIENT"}""")
                    .andExpect(jsonPath("$.ticket.waitingSince").isNotEmpty())
                    // A paused clock is never breached, however old the deadline is.
                    .andExpect(jsonPath("$.ticket.slaBreached").value(false));

            Instant before = Instant.parse(slaDueAt(id));
            backdateWaitingSince(id, 30);

            move(id, """
                    {"status":"IN_PROGRESS"}""").andExpect(jsonPath("$.ticket.waitingSince").isEmpty());

            long paused = pausedSeconds(id);
            assertThat(paused).isPositive();
            assertThat(Instant.parse(slaDueAt(id))).isEqualTo(before.plusSeconds(paused));
        }
    }

    @Nested
    class Permissions {

        /** §6.3: the assignee, the client's owner, or a supervisor over either — nobody else. */
        @Test
        void aBystanderIsRefused() throws Exception {
            String id = raise("MEDIUM");
            patchTicket(id, MARTA_LEWANDOWSKA, """
                    {"status":"IN_PROGRESS"}""")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void theAssigneeMayAct() throws Exception {
            String id = raise("MEDIUM", MARTA_LEWANDOWSKA);
            patchTicket(id, MARTA_LEWANDOWSKA, """
                    {"status":"IN_PROGRESS"}""").andExpect(status().isOk());
        }

        @Test
        void aSupervisorMayAct() throws Exception {
            String id = raise("MEDIUM");
            patchTicket(id, OLA_WISNIEWSKA, "SUPERVISOR", """
                    {"status":"IN_PROGRESS"}""").andExpect(status().isOk());
        }

        /** Nothing on a note has a lifecycle, and ER-01 shapes the answer for a row either way. */
        @Test
        void patchingANoteIsNotFound() throws Exception {
            String id = jsonField(
                    mvc.perform(create("""
                            {"type":"NOTE","direction":"INTERNAL","subject":"A note","body":"Text.",
                             "occurredAt":"%s"}""".formatted(OCCURRED_AT)))
                            .andExpect(status().isCreated())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "id");
            patchTicket(id, ADAM_NOWAK, """
                    {"status":"IN_PROGRESS"}""").andExpect(status().isNotFound());
        }

        @Test
        void anEmptyPatchIsRejected() throws Exception {
            String id = raise("MEDIUM");
            patchTicket(id, ADAM_NOWAK, "{}").andExpect(status().isBadRequest());
        }
    }

    /** Rule 3: the transition and its event commit together, through the outbox. */
    @Test
    void emitsTicketStatusChangedThroughTheOutbox_IL_BR_10() throws Exception {
        String id = raise("MEDIUM");
        jdbc.sql("DELETE FROM interaction.outbox_events").update();
        move(id, """
                {"status":"IN_PROGRESS"}""");
        assertThat(countEvents("ticket.status_changed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String raise(String priority) throws Exception {
        return raise(priority, null);
    }

    private String raise(String priority, UUID assigneeId) throws Exception {
        String assignee = assigneeId == null ? "" : ",\"assigneeId\":\"%s\"".formatted(assigneeId);
        String json = """
                {"type":"TICKET","direction":"INTERNAL","subject":"Card declined abroad",
                 "body":"Declines in Italy.","occurredAt":"%s","ticket":{"priority":"%s"%s}}""".formatted(OCCURRED_AT, priority, assignee);
        return jsonField(
                mvc.perform(create(json))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private ResultActions move(String id, String json) throws Exception {
        return patchTicket(id, ADAM_NOWAK, json).andExpect(status().isOk());
    }

    private ResultActions patchTicket(String id, UUID actor, String json) throws Exception {
        return patchTicket(id, actor, "MANAGER", json);
    }

    private ResultActions patchTicket(String id, UUID actor, String role, String json) throws Exception {
        return mvc.perform(patch("/api/v1/interactions/{id}/ticket", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private org.springframework.test.web.servlet.RequestBuilder create(String json) {
        return post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private String slaDueAt(String id) {
        return jdbc.sql("SELECT sla_due_at FROM interaction.interactions WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant()
                .toString();
    }

    private long pausedSeconds(String id) {
        return jdbc.sql("SELECT sla_paused_seconds FROM interaction.interactions WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    /** Simulates a pause that actually spanned working days, without making the test wait for one. */
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
