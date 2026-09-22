package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * The immutability model of SPEC.md §6.4 — edit inside the window (IL-BR-01/02/04), correct after
 * it (IL-BR-03/05) — and who may read a private note (IL-BR-09).
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class InteractionAmendmentIntegrationTest extends AbstractInteractionIntegrationTest {

    private static final String OCCURRED_AT = "2026-09-09T11:30:00Z";

    @Nested
    class Editing {

        @Test
        void authorRewordsInsideTheWindow_IL_BR_02() throws Exception {
            String id = note(ADAM_NOWAK, "Mortgage rate", "The rate was 5.1%.", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Corrected: the rate was 5.01%.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                    .andExpect(jsonPath("$.body").value("Corrected: the rate was 5.01%."))
                    .andExpect(jsonPath("$.subject").value("Mortgage rate"))
                    .andExpect(jsonPath("$.edited").value(true))
                    .andExpect(jsonPath("$.editCount").value(1))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /** IL-BR-02: measured from createdAt, so yesterday's call written up today is editable. */
        @Test
        void theWindowRunsFromCreationNotFromOccurrence_IL_BR_02() throws Exception {
            String id = note(ADAM_NOWAK, "Yesterday's call", "Body", "TEAM", "2026-09-01T09:00:00Z");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"subject\": \"Last week's call\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        void requiresIfMatch_4_8() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            mvc.perform(patch("/api/v1/interactions/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\": \"x\"}"))
                    .andExpect(status().isPreconditionRequired());
        }

        /** §6.3: nobody silently rewrites another person's record — they correct it, visibly. */
        @Test
        void onlyTheAuthorMayEdit() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            mvc.perform(edit(id, MARTA_LEWANDOWSKA, 0, "{\"body\": \"Rewritten by someone else.\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                    .andExpect(jsonPath("$.details[0].correctionEndpoint")
                            .value("/api/v1/interactions/" + id + "/corrections"));
        }

        /** IL-BR-01: the substance never changes — refused with 422, not ignored and not 400. */
        @Test
        void refusesToChangeTheSubstance_IL_BR_01() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"type\": \"CALL\", \"occurredAt\": \"2026-09-01T09:00:00Z\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"))
                    .andExpect(jsonPath("$.details.length()").value(2));
        }

        @Test
        void rejectsAnUnknownField() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"mood\": \"cheerful\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("mood"));
        }

        /** After the window the refusal names the route that is still open. */
        @Test
        void refusesAfterTheWindowAndPointsAtCorrections_IL_BR_02() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            ageBy(id, "20 minutes");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Too late.\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INTERACTION_EDIT_WINDOW_CLOSED"))
                    .andExpect(jsonPath("$.details[0].correctionEndpoint")
                            .value("/api/v1/interactions/" + id + "/corrections"));
        }

        /** Retrying cannot open a closed window, so reload-and-retry would only waste a round trip. */
        @Test
        void aClosedWindowOutranksAStaleVersion() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            ageBy(id, "20 minutes");
            mvc.perform(edit(id, ADAM_NOWAK, 7, "{\"body\": \"x\"}"))
                    .andExpect(jsonPath("$.code").value("INTERACTION_EDIT_WINDOW_CLOSED"));
        }

        @Test
        void aStaleVersionReturnsTheCurrentText_4_8() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "First wording.", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Second wording.\"}"))
                    .andExpect(status().isOk());
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Third wording.\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                    .andExpect(jsonPath("$.details[0].current.body").value("Second wording."));
        }

        /** IL-BR-04: an edit that changes nothing is not counted against the cap, and emits nothing. */
        @Test
        void anUnchangedEditWritesNothing_IL_BR_04() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Body\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.editCount").value(0));
            assertThat(countEvents("interaction.updated")).isZero();
        }

        /** IL-EC-05 applies to edits too: a card number typed into a fix is masked like any other. */
        @Test
        void masksACardNumberTypedIntoAnEdit_IL_EC_05() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Body", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"Card 4111111111111111 was declined.\"}"))
                    .andExpect(jsonPath("$.body").value("Card ************1111 was declined."))
                    .andExpect(jsonPath("$.maskedCardNumbers").value(1));
        }

        /** IL-BR-10 / AR-01: the event proves the wording changed, never what it said. */
        @Test
        void emitsAMaskedUpdatedEvent_IL_BR_10() throws Exception {
            String id = note(ADAM_NOWAK, "Note", "Original wording.", "TEAM");
            mvc.perform(edit(id, ADAM_NOWAK, 0, "{\"body\": \"New wording.\"}")).andExpect(status().isOk());

            String payload = jdbc.sql(
                            "SELECT payload::text FROM interaction.outbox_events WHERE event_type = 'interaction.updated'")
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains("***MASKED***")
                    .doesNotContain("Original wording")
                    .doesNotContain("New wording");
        }
    }

    @Nested
    class Corrections {

        @Test
        void appendsACorrectionPointingAtTheOriginal_IL_BR_03() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "The rate was 5.1%.");
            mvc.perform(correct(original, ADAM_NOWAK, "Correction: rate", "It was 5.01%, not 5.1%."))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.correctsId").value(original))
                    // A correction of a call is not a second call.
                    .andExpect(jsonPath("$.type").value("NOTE"))
                    .andExpect(jsonPath("$.direction").value("INTERNAL"))
                    // Same moment, so the keyset timeline places the pair side by side.
                    .andExpect(jsonPath("$.occurredAt").value("2026-09-09T11:30:00.000Z"));
        }

        /** IL-BR-03: nothing is ever rewritten in place. */
        @Test
        void leavesTheOriginalUntouched_IL_BR_03() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "The rate was 5.1%.");
            mvc.perform(correct(original, ADAM_NOWAK, "Correction: rate", "It was 5.01%."))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/interactions/{id}", original)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.body").value("The rate was 5.1%."))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.corrections.length()").value(1))
                    .andExpect(jsonPath("$.corrections[0].subject").value("Correction: rate"));
        }

        /** IL-BR-05: one level deep, so "what is currently true" is a single hop. */
        @Test
        void refusesToCorrectACorrection_IL_BR_05() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            String correction = idOf(mvc.perform(correct(original, ADAM_NOWAK, "Correction", "Body"))
                    .andExpect(status().isCreated())
                    .andReturn());

            mvc.perform(correct(correction, ADAM_NOWAK, "Correction of a correction", "Body"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].correctsId").value(original));
        }

        /** Anyone who may write for the client may correct — visibly, as themselves. */
        @Test
        void aColleagueMayCorrectATeamNote() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            mvc.perform(correct(original, MARTA_LEWANDOWSKA, "Correction", "Adam quoted the old rate."))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.author.fullName").value("Marta Lewandowska"));
        }

        /** Correcting a private note must not publish the correction. */
        @Test
        void aCorrectionOfAPrivateNoteStaysPrivate() throws Exception {
            String original = note(ADAM_NOWAK, "Private", "Client seemed evasive.", "PRIVATE");
            mvc.perform(correct(original, ADAM_NOWAK, "Correction", "Not evasive — hard of hearing."))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.visibility").value("PRIVATE"));
        }

        /** CP-BR-08: a closed client's record can still be fixed, because a correction is a note. */
        @Test
        void aClosedClientCanStillBeCorrected_CP_BR_08() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            clientStatus = "CLOSED";
            mvc.perform(correct(original, ADAM_NOWAK, "Correction", "Body")).andExpect(status().isCreated());
        }

        /** IL-BR-03: the feed renders the pair together with the original visibly superseded. */
        @Test
        void theTimelineShowsThePairTogether_IL_BR_03() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            note(ADAM_NOWAK, "Unrelated, later", "Body", "TEAM", "2026-09-10T09:00:00Z");
            mvc.perform(correct(original, ADAM_NOWAK, "Correction", "Body")).andExpect(status().isCreated());

            mvc.perform(get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.content.length()").value(3))
                    .andExpect(jsonPath("$.content[0].subject").value("Unrelated, later"))
                    .andExpect(jsonPath("$.content[1].correctsId").value(original))
                    .andExpect(jsonPath("$.content[2].id").value(original))
                    .andExpect(jsonPath("$.content[2].hasCorrection").value(true));
        }

        @Test
        void emitsACorrectedEvent_IL_BR_10() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();
            mvc.perform(correct(original, ADAM_NOWAK, "Correction", "Body")).andExpect(status().isCreated());
            assertThat(countEvents("interaction.corrected")).isEqualTo(1);
            assertThat(countEvents("interaction.created")).isZero();
        }

        @Test
        void requiresAnIdempotencyKey_4_6() throws Exception {
            String original = call(ADAM_NOWAK, "Mortgage rate", "Body");
            mvc.perform(post("/api/v1/interactions/{id}/corrections", original)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject\": \"Correction\", \"body\": \"Body\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value(IdempotencyService.HEADER));
        }
    }

    @Nested
    class PrivateNotes {

        /** IL-BR-09: an admin may know a private note exists — never what it says. */
        @Test
        void anAdminSeesTheMetadataButNotTheBody_IL_BR_09() throws Exception {
            String id = note(ADAM_NOWAK, "Private", "Client seemed evasive.", "PRIVATE");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();

            mvc.perform(get("/api/v1/interactions/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subject").value("Private"))
                    .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                    .andExpect(jsonPath("$.body").doesNotExist());

            // Their look is still audited: who looked at private notes is exactly what gets asked.
            String context = jdbc.sql(
                            "SELECT payload::text FROM interaction.outbox_events WHERE event_type = 'interaction.read_sensitive'")
                    .query(String.class)
                    .single();
            assertThat(context).contains("interaction.private_metadata");
        }

        @Test
        void anAdminSeesTheRowButNoPreviewInTheTimeline_IL_BR_09() throws Exception {
            note(ADAM_NOWAK, "Private", "Client seemed evasive.", "PRIVATE");
            mvc.perform(get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "ADMIN")))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].subject").value("Private"))
                    .andExpect(jsonPath("$.content[0].bodyPreview").doesNotExist());
        }

        @Test
        void theAuthorStillReadsTheirOwnPrivateNote_IL_BR_09() throws Exception {
            String id = note(ADAM_NOWAK, "Private", "Client seemed evasive.", "PRIVATE");
            mvc.perform(get("/api/v1/interactions/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.body").value("Client seemed evasive."));
        }

        /** An admin cannot amend what they may not read. */
        @Test
        void anAdminCannotCorrectAPrivateNote_IL_BR_09() throws Exception {
            String id = note(ADAM_NOWAK, "Private", "Client seemed evasive.", "PRIVATE");
            mvc.perform(post("/api/v1/interactions/{id}/corrections", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "ADMIN"))
                            .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"subject\": \"Correction\", \"body\": \"Body\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ helpers

    private String note(UUID author, String subject, String body, String visibility) throws Exception {
        return note(author, subject, body, visibility, OCCURRED_AT);
    }

    private String note(UUID author, String subject, String body, String visibility, String occurredAt)
            throws Exception {
        return log(author, "NOTE", "INTERNAL", subject, body, visibility, occurredAt);
    }

    private String call(UUID author, String subject, String body) throws Exception {
        return log(author, "CALL", "OUTBOUND", subject, body, "TEAM", OCCURRED_AT);
    }

    private String log(
            UUID author,
            String type,
            String direction,
            String subject,
            String body,
            String visibility,
            String occurredAt)
            throws Exception {
        String json = """
                {"type": "%s", "direction": "%s", "subject": "%s", "body": "%s",
                 "occurredAt": "%s", "visibility": "%s"}
                """.formatted(type, direction, subject, body, occurredAt, visibility);
        return idOf(mvc.perform(post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(author))
                        .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private MockHttpServletRequestBuilder edit(String id, UUID caller, int version, String body) {
        return patch("/api/v1/interactions/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(caller))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder correct(String id, UUID caller, String subject, String body) {
        return post("/api/v1/interactions/{id}/corrections", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(caller))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\": \"%s\", \"body\": \"%s\"}".formatted(subject, body));
    }

    /** created_at is the window's anchor (IL-BR-02); moving it back is how a test closes the window. */
    private void ageBy(String id, String interval) {
        jdbc.sql(
                        "UPDATE interaction.interactions SET created_at = created_at - CAST(:interval AS interval) WHERE id = :id")
                .param("interval", interval)
                .param("id", UUID.fromString(id))
                .update();
    }

    private static String idOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        String marker = "\"id\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
