package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Soft delete and the full timeline query of SPEC.md §6.3. */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class InteractionDeleteAndFilterIntegrationTest extends AbstractInteractionIntegrationTest {

    private static final String REASON = "Logged against the wrong client";

    @Nested
    class Deleting {

        @Test
        void aSupervisorRemovesAnInteractionFromTheFeed() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Wrong client", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, REASON)).andExpect(status().isNoContent());

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER"))
                    .andExpect(jsonPath("$.content.length()").value(0));
            mvc.perform(get("/api/v1/interactions/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound());
        }

        /** The row stays: audit rows reference it and corrections may point at it. */
        @Test
        void keepsTheRowWithWhoAndWhy() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Wrong client", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, REASON)).andExpect(status().isNoContent());

            String reason = jdbc.sql(
                            "SELECT deletion_reason FROM interaction.interactions WHERE id = :id AND deleted_by = :by")
                    .param("id", UUID.fromString(id))
                    .param("by", OLA_WISNIEWSKA)
                    .query(String.class)
                    .single();
            assertThat(reason).isEqualTo(REASON);
        }

        /** §6.3: managers cannot delete at all — "that is the whole point of an audit trail". */
        @Test
        void aManagerCannotDeleteEvenTheirOwnNote() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Mine", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, ADAM_NOWAK, "MANAGER", 0, REASON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        }

        /** Answered before the lookup, so an unknown id and a known one get the same reply. */
        @Test
        void refusesAManagerBeforeLookingTheInteractionUp() throws Exception {
            mvc.perform(deleteAs(UUID.randomUUID().toString(), ADAM_NOWAK, "MANAGER", 0, REASON))
                    .andExpect(status().isForbidden());
        }

        @Test
        void requiresIfMatch_4_8() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Note", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(delete("/api/v1/interactions/{id}", id)
                            .param("reason", REASON)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isPreconditionRequired());
        }

        @Test
        void aReasonUnderTenCharactersIs422() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Note", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, "oops"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("reason"));
        }

        @Test
        void aStaleVersionIs409() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Note", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 3, REASON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        /**
         * IL-BR-09 holds on the error path too: a conflict carries the current state, and for an
         * admin looking at someone else's private note that state must not include the body.
         */
        @Test
        void aConflictDoesNotLeakAPrivateBody_IL_BR_09() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Private", "Client seemed evasive.", "PRIVATE", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, MARTA_LEWANDOWSKA, "ADMIN", 3, REASON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details[0].current.subject").value("Private"))
                    .andExpect(jsonPath("$.details[0].current.body").doesNotExist());
        }

        /** IL-BR-10: the reason is why it went, so it belongs in the audit context. */
        @Test
        void emitsADeletedEventWithTheReason_IL_BR_10() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Note", "Body", "TEAM", "2026-09-09T11:30:00Z");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, REASON)).andExpect(status().isNoContent());

            String payload = jdbc.sql(
                            "SELECT payload::text FROM interaction.outbox_events WHERE event_type = 'interaction.deleted'")
                    .query(String.class)
                    .single();
            assertThat(payload).contains(REASON).contains("\"DELETE\"");
        }
    }

    @Nested
    class Filtering {

        @Test
        void filtersByMoreThanOneType_IL_US_03() throws Exception {
            log(ADAM_NOWAK, "CALL", "A call", "Body", "TEAM", "2026-09-01T10:00:00Z");
            log(ADAM_NOWAK, "MEETING", "A meeting", "Body", "TEAM", "2026-09-02T10:00:00Z");
            log(ADAM_NOWAK, "NOTE", "A note", "Body", "TEAM", "2026-09-03T10:00:00Z");

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER").param("type", "CALL").param("type", "MEETING"))
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        /** from is inclusive and to exclusive, so consecutive windows neither overlap nor gap. */
        @Test
        void filtersByATimeWindow() throws Exception {
            log(ADAM_NOWAK, "NOTE", "Before", "Body", "TEAM", "2026-09-01T00:00:00Z");
            log(ADAM_NOWAK, "NOTE", "At the start", "Body", "TEAM", "2026-09-02T00:00:00Z");
            log(ADAM_NOWAK, "NOTE", "At the end", "Body", "TEAM", "2026-09-03T00:00:00Z");

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER")
                            .param("from", "2026-09-02T00:00:00Z")
                            .param("to", "2026-09-03T00:00:00Z"))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("At the start"));
        }

        @Test
        void rejectsAWindowThatEndsBeforeItStarts() throws Exception {
            mvc.perform(timeline(ADAM_NOWAK, "MANAGER")
                            .param("from", "2026-09-03T00:00:00Z")
                            .param("to", "2026-09-02T00:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("from"));
        }

        @Test
        void filtersByAuthor() throws Exception {
            log(ADAM_NOWAK, "NOTE", "Adam's", "Body", "TEAM", "2026-09-01T10:00:00Z");
            log(MARTA_LEWANDOWSKA, "NOTE", "Marta's", "Body", "TEAM", "2026-09-02T10:00:00Z");

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER").param("authorId", MARTA_LEWANDOWSKA.toString()))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Marta's"));
        }

        /** §4.7: subjects are searchable, bodies are encrypted and are not. */
        @Test
        void searchesSubjectsButNotBodies() throws Exception {
            log(ADAM_NOWAK, "NOTE", "Mortgage refinancing", "Nothing relevant", "TEAM", "2026-09-01T10:00:00Z");
            log(ADAM_NOWAK, "NOTE", "Card replacement", "The mortgage came up briefly", "TEAM", "2026-09-02T10:00:00Z");

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER").param("q", "MORTGAGE"))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Mortgage refinancing"));
        }

        /** A percent sign the manager typed is a percent sign, not "match everything". */
        @Test
        void treatsAPercentSignInTheQueryLiterally() throws Exception {
            log(ADAM_NOWAK, "NOTE", "Rate 5% agreed", "Body", "TEAM", "2026-09-01T10:00:00Z");
            log(ADAM_NOWAK, "NOTE", "Rate 50 bps discussed", "Body", "TEAM", "2026-09-02T10:00:00Z");

            mvc.perform(timeline(ADAM_NOWAK, "MANAGER").param("q", "5%"))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Rate 5% agreed"));
        }
    }

    @Nested
    class IncludeDeleted {

        /** §6.3: an auditor sees the removed row struck through, with who removed it and why. */
        @Test
        void anAuditorSeesDeletedRowsLabelled() throws Exception {
            String id = log(ADAM_NOWAK, "NOTE", "Wrong client", "Body", "TEAM", "2026-09-09T11:30:00Z");
            mvc.perform(deleteAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, REASON)).andExpect(status().isNoContent());

            mvc.perform(timeline(MARTA_LEWANDOWSKA, "AUDITOR").param("includeDeleted", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].deleted").value(true))
                    .andExpect(jsonPath("$.content[0].deletedBy.fullName").value("Ola Wiśniewska"))
                    .andExpect(jsonPath("$.content[0].deletionReason").value(REASON));
        }

        /** A supervisor holds audit:read at TEAM, not ALL, so this view is not theirs. */
        @Test
        void aSupervisorMayNotIncludeDeleted() throws Exception {
            mvc.perform(timeline(OLA_WISNIEWSKA, "SUPERVISOR").param("includeDeleted", "true"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void aManagerMayNotIncludeDeleted() throws Exception {
            mvc.perform(timeline(ADAM_NOWAK, "MANAGER").param("includeDeleted", "true"))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder timeline(UUID caller, String role) {
        return get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(caller, role));
    }

    private MockHttpServletRequestBuilder deleteAs(String id, UUID caller, String role, int version, String reason) {
        return delete("/api/v1/interactions/{id}", id)
                .param("reason", reason)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(caller, role))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"");
    }

    private String log(UUID author, String type, String subject, String body, String visibility, String occurredAt)
            throws Exception {
        String direction = "NOTE".equals(type) ? "INTERNAL" : "OUTBOUND";
        String json = """
                {"type": "%s", "direction": "%s", "subject": "%s", "body": "%s",
                 "occurredAt": "%s", "visibility": "%s"}
                """.formatted(type, direction, subject, body, occurredAt, visibility);
        MvcResult result = mvc.perform(post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(author))
                        .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        String marker = "\"id\":\"";
        int start = response.indexOf(marker) + marker.length();
        return response.substring(start, response.indexOf('"', start));
    }
}
