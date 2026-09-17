package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Interaction Log (SPEC.md §6) end to end, against a real PostgreSQL. */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class InteractionIntegrationTest extends AbstractInteractionIntegrationTest {

    @Nested
    class Logging {

        @Test
        void logsACallAgainstTheClient_IL_US_02() throws Exception {
            mvc.perform(create(body("CALL", "OUTBOUND", "Mortgage rate question", "Client asked about refinancing.")))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.type").value("CALL"))
                    .andExpect(jsonPath("$.subject").value("Mortgage rate question"))
                    .andExpect(jsonPath("$.body").value("Client asked about refinancing."))
                    .andExpect(jsonPath("$.source").value("WEB"))
                    .andExpect(jsonPath("$.author.fullName").value("Adam Nowak"))
                    .andExpect(jsonPath("$.editableUntil").exists())
                    .andExpect(jsonPath("$.version").value(0));
        }

        /** IL-BR-02: the window runs from createdAt, so a late write-up is still editable. */
        @Test
        void opensAFifteenMinuteEditWindowFromCreation_IL_BR_02() throws Exception {
            MvcResult result = mvc.perform(create(body("NOTE", "INTERNAL", "Left a message", "Will retry tomorrow.")))
                    .andExpect(status().isCreated())
                    .andReturn();
            String json = result.getResponse().getContentAsString();
            Instant createdAt = Instant.parse(jsonField(json, "createdAt"));
            Instant editableUntil = Instant.parse(jsonField(json, "editableUntil"));
            assertThat(ChronoUnit.MINUTES.between(createdAt, editableUntil)).isEqualTo(15);
        }

        @Test
        void rejectsADurationOnANote() throws Exception {
            String json = body("NOTE", "INTERNAL", "Note", "Body")
                    .replace("\"durationSeconds\": null", "\"durationSeconds\": 60");
            mvc.perform(create(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("durationSeconds"));
        }

        /** IL-EC-02: five minutes of clock skew is tolerated, the future is not loggable. */
        @Test
        void rejectsAnInteractionFromTheFuture_IL_EC_02() throws Exception {
            String json = body("CALL", "OUTBOUND", "Future call", "Body")
                    .replace(
                            "\"occurredAt\": \"" + OCCURRED_AT + "\"",
                            "\"occurredAt\": \"" + Instant.now().plus(2, ChronoUnit.HOURS) + "\"");
            mvc.perform(create(json))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("occurredAt"));
        }

        /** CP-BR-08: a closed client still accepts a note — the manager records why it is closed. */
        @Test
        void aClosedClientAcceptsOnlyNotes_CP_BR_08() throws Exception {
            clientStatus = "CLOSED";
            mvc.perform(create(body("CALL", "OUTBOUND", "Call", "Body")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
            mvc.perform(create(body("NOTE", "INTERNAL", "Note", "Body"))).andExpect(status().isCreated());
        }

        /** ER-01: client-service's 404 passes through unchanged rather than becoming a 403. */
        @Test
        void anOutOfScopeClientIs404_ER_01() throws Exception {
            clientInScope = false;
            mvc.perform(create(body("NOTE", "INTERNAL", "Note", "Body")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        @Test
        void requiresAnIdempotencyKey_4_6() throws Exception {
            mvc.perform(post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("NOTE", "INTERNAL", "Note", "Body")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value(IdempotencyService.HEADER));
        }
    }

    @Nested
    class Storage {

        /** §4.7: the body is encrypted at rest; the subject is plaintext and searchable. */
        @Test
        void storesTheBodyEncrypted_4_7() throws Exception {
            mvc.perform(create(body("NOTE", "INTERNAL", "Mortgage", "Client is refinancing at 5.1%.")))
                    .andExpect(status().isCreated());

            byte[] stored = jdbc.sql("SELECT body_enc FROM interaction.interactions")
                    .query(byte[].class)
                    .single();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("refinancing");

            String subject = jdbc.sql("SELECT subject FROM interaction.interactions")
                    .query(String.class)
                    .single();
            assertThat(subject).isEqualTo("Mortgage");
        }

        /** IL-EC-05: a card number is masked before encryption, and the caller is told. */
        @Test
        void masksCardNumbersBeforeStoring_IL_EC_05() throws Exception {
            mvc.perform(create(
                            body("CALL", "INBOUND", "Card query", "Client read out 4111 1111 1111 1111 on the call.")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.maskedCardNumbers").value(1))
                    .andExpect(jsonPath("$.body").value("Client read out ************1111 on the call."));

            byte[] stored = jdbc.sql("SELECT body_enc FROM interaction.interactions")
                    .query(byte[].class)
                    .single();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("4111");
        }

        /** A number that fails Luhn is not a card number and must survive untouched. */
        @Test
        void leavesNonCardDigitRunsAlone_IL_EC_05() throws Exception {
            mvc.perform(create(body("NOTE", "INTERNAL", "Reference", "Case reference 1234567890123 noted.")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.maskedCardNumbers").doesNotExist())
                    .andExpect(jsonPath("$.body").value("Case reference 1234567890123 noted."));
        }

        /** AR-01: the event records that a note was written, never what it said. */
        @Test
        void masksSubjectAndBodyInTheEvent_AR_01() throws Exception {
            mvc.perform(create(body("NOTE", "INTERNAL", "Mortgage rate question", "Client is refinancing.")))
                    .andExpect(status().isCreated());

            String payload = jdbc.sql(
                            "SELECT payload::text FROM interaction.outbox_events WHERE event_type = 'interaction.created'")
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains("***MASKED***")
                    .doesNotContain("Mortgage rate question")
                    .doesNotContain("refinancing");
            assertThat(payload.replace(" ", "")).contains("\"type\":{\"new\":\"NOTE\",\"old\":null}");
        }
    }

    @Nested
    class Timeline {

        @Test
        void returnsNewestFirstWithAPreview_IL_BR_11() throws Exception {
            log("NOTE", "Older", "First body", "2026-09-01T10:00:00Z");
            log("CALL", "Newer", "Second body", "2026-09-05T10:00:00Z");

            mvc.perform(timeline())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].subject").value("Newer"))
                    .andExpect(jsonPath("$.content[0].bodyPreview").value("Second body"))
                    .andExpect(jsonPath("$.content[0].author.fullName").value("Adam Nowak"))
                    // A preview is not the body: the full text is not on this response.
                    .andExpect(jsonPath("$.content[0].body").doesNotExist())
                    .andExpect(jsonPath("$.hasMore").value(false));
        }

        /** IL-BR-11: scrolling a feed is not a disclosure and must not flood the audit log. */
        @Test
        void emitsNoAuditEvent_IL_BR_11() throws Exception {
            log("NOTE", "Note", "Body", "2026-09-01T10:00:00Z");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();

            mvc.perform(timeline()).andExpect(status().isOk());
            assertThat(countEvents("interaction.read_sensitive")).isZero();
        }

        @Test
        void pagesWithAKeysetCursor_4_5() throws Exception {
            log("NOTE", "One", "Body", "2026-09-01T10:00:00Z");
            log("NOTE", "Two", "Body", "2026-09-02T10:00:00Z");
            log("NOTE", "Three", "Body", "2026-09-03T10:00:00Z");

            MvcResult first = mvc.perform(timeline().param("limit", "2"))
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].subject").value("Three"))
                    .andExpect(jsonPath("$.hasMore").value(true))
                    .andReturn();
            String cursor = jsonField(first.getResponse().getContentAsString(), "nextCursor");

            mvc.perform(timeline().param("limit", "2").param("cursor", cursor))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("One"))
                    .andExpect(jsonPath("$.hasMore").value(false));
        }

        @Test
        void rejectsAMalformedCursor_4_5() throws Exception {
            mvc.perform(timeline().param("cursor", "not-a-cursor"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }

        @Test
        void filtersByType_IL_US_03() throws Exception {
            log("NOTE", "A note", "Body", "2026-09-01T10:00:00Z");
            log("CALL", "A call", "Body", "2026-09-02T10:00:00Z");

            mvc.perform(timeline().param("type", "CALL"))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("A call"));
        }

        /** IL-BR-09: supervisors do not see private notes. A product decision, not a bug. */
        @Test
        void hidesAnotherAuthorsPrivateNote_IL_BR_09() throws Exception {
            mvc.perform(create(body("NOTE", "INTERNAL", "Private thought", "Client seemed evasive.")
                            .replace("\"visibility\": \"TEAM\"", "\"visibility\": \"PRIVATE\"")))
                    .andExpect(status().isCreated());

            mvc.perform(timeline()).andExpect(jsonPath("$.content.length()").value(1));
            mvc.perform(get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA)))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    @Nested
    class Reading {

        /** IL-BR-11: opening the body is the disclosure, and it is audited. */
        @Test
        void auditsOpeningTheFullBody_IL_BR_11() throws Exception {
            String id = log("NOTE", "Note", "The whole body.", "2026-09-01T10:00:00Z");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();

            mvc.perform(get("/api/v1/interactions/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("The whole body."));

            assertThat(countEvents("interaction.read_sensitive")).isEqualTo(1);
        }

        @Test
        void unknownInteractionIs404() throws Exception {
            mvc.perform(get("/api/v1/interactions/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INTERACTION_NOT_FOUND"));
        }

        /** A private note another user cannot see is 404, not 403 — existence stays hidden. */
        @Test
        void anotherAuthorsPrivateNoteIs404_IL_BR_09() throws Exception {
            String id = log("NOTE", "Private", "Secret.", "2026-09-01T10:00:00Z");
            jdbc.sql("UPDATE interaction.interactions SET visibility = 'PRIVATE' WHERE id = :id")
                    .param("id", UUID.fromString(id))
                    .update();

            mvc.perform(get("/api/v1/interactions/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("INTERACTION_NOT_FOUND"));
            assertThat(countEvents("interaction.permission_denied")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final String OCCURRED_AT = "2026-09-09T11:30:00Z";

    private MockHttpServletRequestBuilder timeline() {
        return get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK));
    }

    private String log(String type, String subject, String body, String occurredAt) throws Exception {
        String direction = "NOTE".equals(type) ? "INTERNAL" : "OUTBOUND";
        String json = body(type, direction, subject, body).replace(OCCURRED_AT, occurredAt);
        MvcResult result =
                mvc.perform(create(json)).andExpect(status().isCreated()).andReturn();
        return jsonField(result.getResponse().getContentAsString(), "id");
    }

    private MockHttpServletRequestBuilder create(String json) {
        return post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private static String body(String type, String direction, String subject, String body) {
        return """
                {
                  "type": "%s",
                  "direction": "%s",
                  "subject": "%s",
                  "body": "%s",
                  "occurredAt": "%s",
                  "durationSeconds": null,
                  "outcome": "SUCCESSFUL",
                  "visibility": "TEAM"
                }
                """.formatted(type, direction, subject, body, OCCURRED_AT);
    }

    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
