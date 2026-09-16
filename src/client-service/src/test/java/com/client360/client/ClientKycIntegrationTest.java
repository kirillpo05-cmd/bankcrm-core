package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code POST /clients/{id}/kyc} — the CP-BR-04 state machine under CP-BR-05's separation of duties. */
@Import(MatrixAccessPolicy.Config.class)
class ClientKycIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1);

    @Nested
    class SeparationOfDuties {

        /** CP-BR-05: starting a review is an ordinary edit, so a manager may do it. */
        @Test
        void managerMayStartTheReview_CP_BR_05() throws Exception {
            String id = ownedByAdam();
            mvc.perform(kyc(id, ADAM_NOWAK, "MANAGER", """
                            {"targetStatus": "PENDING"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.status").value("PENDING"));
        }

        /** CP-BR-05: approving it is not — that needs client:kyc, which a manager does not hold. */
        @Test
        void managerMayNotApprove_CP_BR_05() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, ADAM_NOWAK, "MANAGER", verifiedBody(24, "Passport on file")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"))
                    .andExpect(jsonPath("$.details[0].permission").value("client:kyc"));
        }

        @Test
        void supervisorApprovesAClientOfTheirTeam_CP_BR_04() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, "Passport + utility bill on file")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.status").value("VERIFIED"))
                    .andExpect(jsonPath("$.kyc.verifiedAt").exists())
                    .andExpect(jsonPath("$.kyc.expiresAt").exists())
                    .andExpect(jsonPath("$.kyc.daysUntilExpiry").isNumber());
        }

        /**
         * CP-BR-05: holding the permission is not enough. Checked against the owner at request
         * time, which is why a supervisor who happens to own the client is refused.
         */
        @Test
        void ownerMayNotApproveTheirOwnClient_CP_BR_05() throws Exception {
            String id = createClient(
                    OLA_WISNIEWSKA, "SUPERVISOR", OLA_WISNIEWSKA, "CIF-77", "own@example.com", "+48511234599");
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", "{\"targetStatus\": \"PENDING\"}"))
                    .andExpect(status().isOk());

            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                    .andExpect(jsonPath("$.details[0].reason").value("SELF_APPROVAL_FORBIDDEN"));
        }

        /** ER-01: a supervisor of another team cannot even see the record, so it is a 404. */
        @Test
        void supervisorOfAnotherTeamGets404_ER_01() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, PIOTR_KOWALCZYK, "SUPERVISOR", verifiedBody(24, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /** Compliance holds client:kyc at ALL scope and owns no clients, so it can always approve. */
        @Test
        void complianceMayApproveAnyClient() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, EWA_ZGODNOSC, "COMPLIANCE", verifiedBody(12, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.status").value("VERIFIED"));
        }
    }

    @Nested
    class Transitions {

        @Test
        void refusesToSkipPending_CP_BR_04() throws Exception {
            String id = ownedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"))
                    .andExpect(jsonPath("$.details[0].from").value("NOT_STARTED"))
                    .andExpect(jsonPath("$.details[0].to").value("VERIFIED"));
        }

        /** CP-BR-06: expiry belongs to the nightly sweep; no caller may drive it. */
        @Test
        void refusesExpiredAsATarget_CP_BR_06() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", "{\"targetStatus\": \"EXPIRED\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        @Test
        void rejectsRepeatingTheSameStatus_CP_BR_04() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, ADAM_NOWAK, "MANAGER", "{\"targetStatus\": \"PENDING\"}"))
                    .andExpect(status().isConflict());
        }

        @Test
        void rejectionRecordsTheReason_CP_BR_04() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", """
                            {"targetStatus": "REJECTED", "reason": "Document expired"}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.status").value("REJECTED"))
                    .andExpect(jsonPath("$.kyc.rejectionReason").value("Document expired"));
        }

        /** Leaving VERIFIED clears the dates, so a PENDING client never shows a stale expiry. */
        @Test
        void reVerificationClearsTheOldDates() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, null)))
                    .andExpect(status().isOk());
            mvc.perform(kyc(id, ADAM_NOWAK, "MANAGER", "{\"targetStatus\": \"PENDING\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.status").value("PENDING"))
                    .andExpect(jsonPath("$.kyc.expiresAt").doesNotExist())
                    .andExpect(jsonPath("$.kyc.daysUntilExpiry").doesNotExist());
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectionWithoutAReasonIs422() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", "{\"targetStatus\": \"REJECTED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("reason"));
        }

        @Test
        void validityOutsideSixToSixtyMonthsIs422() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(5, null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("validityMonths"));
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(61, null)))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void verificationWithoutDatesIs422() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", "{\"targetStatus\": \"VERIFIED\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("verifiedOn"));
        }

        @Test
        void aFutureVerificationDateIs422() throws Exception {
            String id = pendingOwnedByAdam();
            String body = """
                    {"targetStatus": "VERIFIED", "verifiedOn": "%s", "validityMonths": 24}
                    """.formatted(LocalDate.now(ZoneOffset.UTC).plusDays(2));
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("verifiedOn"));
        }

        /** A verification that expired before it was recorded is a green badge that is already false. */
        @Test
        void anAlreadyExpiredVerificationIs422() throws Exception {
            String id = pendingOwnedByAdam();
            String body = """
                    {"targetStatus": "VERIFIED", "verifiedOn": "%s", "validityMonths": 12}
                    """.formatted(LocalDate.now(ZoneOffset.UTC).minusYears(5));
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", body)).andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Storage {

        /** §5.3: the note is stored, and stored encrypted — it describes identity documents. */
        @Test
        void storesTheNoteEncryptedAndReturnsIt() throws Exception {
            String id = pendingOwnedByAdam();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, "Passport AB1234567 on file")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kyc.note").value("Passport AB1234567 on file"));

            byte[] stored = jdbc.sql("SELECT kyc_note_enc FROM client.clients WHERE id = :id")
                    .param("id", UUID.fromString(id))
                    .query(byte[].class)
                    .single();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("AB1234567");
        }

        /** AR-01: the decision is auditable, the document number is not on the bus. */
        @Test
        void emitsKycChangedWithTheNoteMasked_AR_01() throws Exception {
            String id = pendingOwnedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(kyc(id, OLA_WISNIEWSKA, "SUPERVISOR", verifiedBody(24, "Passport AB1234567 on file")))
                    .andExpect(status().isOk());

            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.kyc_changed'")
                    .query(String.class)
                    .single();
            assertThat(payload).contains("***MASKED***").doesNotContain("AB1234567");
            assertThat(payload.replace(" ", "")).contains("\"kycStatus\":{\"new\":\"VERIFIED\",\"old\":\"PENDING\"}");
        }

        @Test
        void schemaHasNoPlaintextKycNoteColumn_rule_8() {
            Integer columns = jdbc.sql("""
                            SELECT count(*) FROM information_schema.columns
                             WHERE table_schema = 'client' AND table_name = 'clients'
                               AND column_name = 'kyc_note'
                            """).query(Integer.class).single();
            assertThat(columns).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ownedByAdam() throws Exception {
        return createClient(ADAM_NOWAK, "CIF-0092841", "anna.kowalska@example.com", "+48511234567");
    }

    private String pendingOwnedByAdam() throws Exception {
        String id = ownedByAdam();
        mvc.perform(kyc(id, ADAM_NOWAK, "MANAGER", "{\"targetStatus\": \"PENDING\"}"))
                .andExpect(status().isOk());
        return id;
    }

    private static String verifiedBody(int months, String note) {
        String noteJson = note == null ? "" : ", \"note\": \"%s\"".formatted(note);
        return """
                {"targetStatus": "VERIFIED", "verifiedOn": "%s", "validityMonths": %d%s}
                """.formatted(YESTERDAY, months, noteJson);
    }

    private MockHttpServletRequestBuilder kyc(String id, UUID actor, String role, String body) {
        return post("/api/v1/clients/{id}/kyc", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
