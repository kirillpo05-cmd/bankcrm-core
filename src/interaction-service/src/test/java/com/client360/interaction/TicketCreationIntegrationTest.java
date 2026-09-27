package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Raising a ticket (SPEC.md §6.3, IL-US-05) and the SLA it is given (IL-BR-07).
 *
 * <p>The instants here are not arbitrary. {@code occurredAt} is Monday 7 September 2026 at 09:00
 * UTC, which is 11:00 in Europe/Warsaw — the zone the doubled client-service reports for the
 * owning team. Every expected deadline below is worked out from that in business hours by hand,
 * not by calling the same arithmetic the code under test uses.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class TicketCreationIntegrationTest extends AbstractInteractionIntegrationTest {

    /** Monday 11:00 Warsaw. The office is open, so the clock starts immediately. */
    private static final String OCCURRED_AT = "2026-09-07T09:00:00Z";

    @Nested
    class Sla {

        /** CRITICAL is 4 business hours: Monday 11:00 + 4 = Monday 15:00 Warsaw = 13:00 UTC. */
        @Test
        void criticalIsFourBusinessHours_IL_BR_07() throws Exception {
            mvc.perform(ticket("CRITICAL", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("TICKET"))
                    .andExpect(jsonPath("$.ticket.priority").value("CRITICAL"))
                    .andExpect(jsonPath("$.ticket.slaDueAt").value("2026-09-07T13:00:00.000Z"));
        }

        /**
         * HIGH is 24 business hours, which is three working days and not one calendar day: seven
         * hours left on Monday, nine on Tuesday, eight on Wednesday — Wednesday 17:00 Warsaw.
         */
        @Test
        void highIsTwentyFourBusinessHoursNotOneDay_IL_BR_07() throws Exception {
            mvc.perform(ticket("HIGH", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.slaDueAt").value("2026-09-09T15:00:00.000Z"));
        }

        /** The deadline is stored, not recomputed per read: it is frozen at creation (IL-BR-07). */
        @Test
        void theDeadlineIsPersisted_IL_BR_07() throws Exception {
            mvc.perform(ticket("CRITICAL", null)).andExpect(status().isCreated());
            assertThat(jdbc.sql("SELECT count(*) FROM interaction.interactions"
                                    + " WHERE type = 'TICKET' AND sla_due_at IS NOT NULL")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }

        /**
         * A ticket raised this long ago is already past a CRITICAL deadline, and the card says so
         * without anything having swept it — {@code slaBreached} is computed, never stored.
         */
        @Test
        void anOldTicketIsBreachedOnArrival() throws Exception {
            mvc.perform(ticket("CRITICAL", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.slaBreached").value(true));
        }
    }

    @Nested
    class Shape {

        /** IL-BR-08: every ticket enters the state machine at the same place. */
        @Test
        void startsAtNewAndOffersOnlyTheLegalTargets_IL_BR_08() throws Exception {
            mvc.perform(ticket("MEDIUM", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.status").value("NEW"))
                    .andExpect(jsonPath("$.ticket.legalTargets.length()").value(2))
                    .andExpect(jsonPath("$.ticket.resolvedAt").value(nullValue()))
                    .andExpect(jsonPath("$.ticket.slaPausedSeconds").value(0));
        }

        /** An unassigned ticket sits in the queue. That is a state, not a validation failure. */
        @Test
        void mayBeCreatedUnassigned() throws Exception {
            mvc.perform(ticket("LOW", null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.assignee").value(nullValue()));
        }

        @Test
        void resolvesTheAssigneesName() throws Exception {
            mvc.perform(ticket("LOW", MARTA_LEWANDOWSKA))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.ticket.assignee.id").value(MARTA_LEWANDOWSKA.toString()));
        }

        /** Everything that is not a ticket has no lifecycle, and says so rather than omitting it. */
        @Test
        void aNoteCarriesNoTicketBlock() throws Exception {
            mvc.perform(note())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("NOTE"))
                    .andExpect(jsonPath("$.ticket").value(nullValue()));
        }

        /** The feed renders an SLA badge from this, so the row carries the block too (S-IL-01). */
        @Test
        void theTimelineRowCarriesTheTicketBlock() throws Exception {
            mvc.perform(ticket("HIGH", MARTA_LEWANDOWSKA)).andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].ticket.priority").value("HIGH"))
                    .andExpect(jsonPath("$.content[0].ticket.assignee.fullName").value("Marta Lewandowska"));
        }
    }

    @Nested
    class Validation {

        /** ck_interactions_ticket_fields, answered at the API edge so the field is named. */
        @Test
        void ticketTypeWithoutATicketBlockIsRejected() throws Exception {
            mvc.perform(body("""
                            {"type":"TICKET","direction":"INTERNAL","subject":"Card declined abroad",
                             "body":"Declines in Italy.","occurredAt":"%s"}
                            """.formatted(OCCURRED_AT)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("ticket"));
        }

        @Test
        void aTicketBlockOnANoteIsRejected() throws Exception {
            mvc.perform(body("""
                            {"type":"NOTE","direction":"INTERNAL","subject":"Just a note",
                             "body":"No lifecycle here.","occurredAt":"%s",
                             "ticket":{"priority":"HIGH"}}
                            """.formatted(OCCURRED_AT)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("ticket"));
        }

        /** Priority is what the SLA is derived from, so there is no default to fall back on. */
        @Test
        void aTicketWithoutAPriorityIsRejected() throws Exception {
            mvc.perform(body("""
                            {"type":"TICKET","direction":"INTERNAL","subject":"Card declined abroad",
                             "body":"Declines in Italy.","occurredAt":"%s","ticket":{}}
                            """.formatted(OCCURRED_AT))).andExpect(status().isBadRequest());
        }
    }

    /**
     * IL-BR-03: correcting a ticket's wording produces a NOTE. Forking the lifecycle would give one
     * issue two SLA clocks, and nothing would say which one the bank is judged against.
     */
    @Test
    void aCorrectionOfATicketIsANoteWithNoLifecycle_IL_BR_03() throws Exception {
        String id = jsonField(
                mvc.perform(ticket("HIGH", null))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");

        mvc.perform(post("/api/v1/interactions/{id}/corrections", id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                        .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"Card declined in Italy","body":"Corrected: Italy, not Spain."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NOTE"))
                .andExpect(jsonPath("$.ticket").value(nullValue()));
    }

    private MockHttpServletRequestBuilder ticket(String priority, UUID assigneeId) {
        String assignee = assigneeId == null ? "" : ",\"assigneeId\":\"%s\"".formatted(assigneeId);
        return body("""
                {"type":"TICKET","direction":"INTERNAL","subject":"Card declined abroad",
                 "body":"Client reports declines in Italy.","occurredAt":"%s",
                 "ticket":{"priority":"%s"%s}}
                """.formatted(OCCURRED_AT, priority, assignee));
    }

    private MockHttpServletRequestBuilder note() {
        return body("""
                {"type":"NOTE","direction":"INTERNAL","subject":"Left a voicemail",
                 "body":"No answer.","occurredAt":"%s"}
                """.formatted(OCCURRED_AT));
    }

    private MockHttpServletRequestBuilder body(String json) {
        return post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
