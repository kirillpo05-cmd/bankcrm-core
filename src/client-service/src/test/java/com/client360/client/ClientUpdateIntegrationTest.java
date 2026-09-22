package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code PATCH /clients/{id}} — CP-US-03, §4.8 and the §5.3 field-level rules. */
@Import(MatrixAccessPolicy.Config.class)
class ClientUpdateIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Editing {

        @Test
        void updatesContactDetailsAndBumpsVersion_CP_US_03() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"email": "a.kowalska@newmail.com", "preferredChannel": "EMAIL", "segment": "PREMIUM"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                    .andExpect(jsonPath("$.email").value("a.kowalska@newmail.com"))
                    .andExpect(jsonPath("$.preferredChannel").value("EMAIL"))
                    .andExpect(jsonPath("$.segment").value("PREMIUM"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        /** An explicit null clears a nullable field; absence leaves it alone. */
        @Test
        void explicitNullClearsANullableField() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"address": null}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.address").doesNotExist())
                    .andExpect(jsonPath("$.email").value("anna.kowalska@example.com"));
        }

        @Test
        void rejectsNullOnANonNullableField() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"firstName": null}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details[0].field").value("firstName"));
        }

        @Test
        void rejectsAnUnknownField() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"nickname": "Ania"}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("nickname"));
        }

        /** Every bad field is reported at once, not one per round trip. */
        @Test
        void reportsEveryProblemInOnePass() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"email": "not-an-email", "dateOfBirth": "yesterday"}
                            """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.length()").value(2));
        }

        /** CP-BR-12 counts writes, not requests: a patch that changes nothing writes nothing. */
        @Test
        void aNoOpPatchWritesNothing_CP_BR_12() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"segment": "RETAIL"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0));
            assertThat(countEvents("client.updated")).isZero();
        }

        @Test
        void rejectsAnEmailAlreadyHeldByAnotherClient_CP_BR_02() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "taken@example.com", "+48511234567");
            String id = createClient(ADAM_NOWAK, "CIF-2", "mine@example.com", "+48511234568");
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"email": "taken@example.com"}
                            """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CLIENT_DUPLICATE_EMAIL"));
        }

        /** Keeping your own email is not a duplicate of yourself. */
        @Test
        void allowsRewritingTheSameEmail() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"email": "anna.kowalska@example.com", "segment": "PREMIUM"}
                            """)).andExpect(status().isOk());
        }

        /** AR-01: the event records that the email changed, never to what. */
        @Test
        void masksSensitiveValuesInTheUpdateEvent_AR_01() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"email": "a.kowalska@newmail.com", "segment": "PREMIUM"}
                            """)).andExpect(status().isOk());
            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.updated'")
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains("***MASKED***")
                    .doesNotContain("a.kowalska@newmail.com")
                    .doesNotContain("anna.kowalska@example.com");
            // Non-sensitive fields keep their real values, or the audit trail says nothing useful.
            assertThat(payload.replace(" ", "")).contains("\"segment\":{\"new\":\"PREMIUM\",\"old\":\"RETAIL\"}");
        }
    }

    @Nested
    class OptimisticLocking {

        @Test
        void requiresIfMatch_4_8() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patch("/api/v1/clients/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"segment\": \"PREMIUM\"}"))
                    .andExpect(status().isPreconditionRequired())
                    .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        }

        /** CP-EC-01: the second writer is refused and handed the current state to diff against. */
        @Test
        void rejectsAStaleVersionAndReturnsCurrentState_CP_EC_01() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"segment": "PREMIUM"}
                            """)).andExpect(status().isOk());

            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, """
                            {"segment": "PRIVATE"}
                            """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                    .andExpect(jsonPath("$.details[0].current.id").value(id))
                    .andExpect(jsonPath("$.details[0].current.segment").value("PREMIUM"))
                    .andExpect(jsonPath("$.details[0].current.version").value(1));
        }

        /** CP-BR-13: that conflict body hands over decrypted PII, so it is audited like a read. */
        @Test
        void auditsTheDisclosureInAConflictBody_CP_BR_13() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, "{\"segment\": \"PREMIUM\"}"))
                    .andExpect(status().isOk());
            jdbc.sql("DELETE FROM client.outbox_events").update();

            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, "{\"segment\": \"PRIVATE\"}"))
                    .andExpect(status().isConflict());

            assertThat(countEvents("client.read_sensitive"))
                    .as("the audit row must survive the rollback of the rejected request")
                    .isEqualTo(1);
        }
    }

    @Nested
    class FieldAuthority {

        @Test
        void refusesOwnerManagerIdThroughPatch_CP_BR_03() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, """
                            {"ownerManagerId": "%s"}
                            """.formatted(MARTA_LEWANDOWSKA)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void refusesTeamIdThroughPatch_CP_BR_03() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, """
                            {"teamId": "%s"}
                            """.formatted(TEAM_RWS)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void refusesKycStatusThroughPatch_CP_BR_04() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, """
                            {"kycStatus": "VERIFIED"}
                            """)).andExpect(status().isForbidden());
        }

        @Test
        void refusesExternalRefChange_CP_BR_01() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, """
                            {"externalRef": "CIF-9999"}
                            """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        /** A-13: a risk rating is a compliance judgement, so it needs client:kyc. */
        @Test
        void managerCannotChangeRisk_A_13() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, "{\"risk\": \"HIGH\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.details[0].permission").value("client:kyc"));
        }

        @Test
        void supervisorCanChangeRisk_A_13() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, OLA_WISNIEWSKA, "SUPERVISOR", 0, "{\"risk\": \"HIGH\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.risk").value("HIGH"));
        }

        @Test
        void managerCannotChangeStatus_A_13() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, "{\"status\": \"DORMANT\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.details[0].permission").value("client:delete"));
        }

        @Test
        void adminCanChangeStatus_A_13() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "{\"status\": \"DORMANT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DORMANT"));
        }

        /** ER-01: a manager from another team learns nothing, and the denial is still audited. */
        @Test
        void outOfScopeUpdateIs404AndAudited_ER_01() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(patchAs(id, JAN_ZIELINSKI, "MANAGER", 0, "{\"segment\": \"PREMIUM\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
            assertThat(countEvents("client.permission_denied")).isEqualTo(1);
        }
    }

    @Nested
    class ClosedClients {

        @Test
        void refusesEditsOnAClosedClient_CP_BR_08() throws Exception {
            String id = close(ownedByAdam());
            mvc.perform(patchAs(id, ADAM_NOWAK, "MANAGER", 0, "{\"segment\": \"PREMIUM\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        @Test
        void allowsReopeningAClosedClient_CP_BR_08() throws Exception {
            String id = close(ownedByAdam());
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "{\"status\": \"ACTIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        /** Reopening is the whole edit or it is not a reopening. */
        @Test
        void refusesReopeningBundledWithOtherEdits_CP_BR_08() throws Exception {
            String id = close(ownedByAdam());
            mvc.perform(patchAs(id, SOFIA_ADAMSKA, "ADMIN", 0, """
                            {"status": "ACTIVE", "segment": "PREMIUM"}
                            """)).andExpect(status().isUnprocessableEntity());
        }

        private String close(String id) {
            jdbc.sql("UPDATE client.clients SET status = 'CLOSED' WHERE id = :id")
                    .param("id", UUID.fromString(id))
                    .update();
            return id;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ownedByAdam() throws Exception {
        return createClient(ADAM_NOWAK, "CIF-0092841", "anna.kowalska@example.com", "+48511234567");
    }

    private MockHttpServletRequestBuilder patchAs(String id, UUID actor, String role, int version, String body) {
        return patch("/api/v1/clients/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
