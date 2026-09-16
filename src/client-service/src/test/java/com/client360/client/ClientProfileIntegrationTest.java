package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Client Profile (SPEC.md §5) end to end, against a real PostgreSQL. */
class ClientProfileIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Create {

        @Test
        void createsClientAndReturnsCard() throws Exception {
            mvc.perform(create(body("CIF-0092841", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.externalRef").value("CIF-0092841"))
                    .andExpect(jsonPath("$.displayName").value("Anna Kowalska"))
                    .andExpect(jsonPath("$.email").value("anna.kowalska@example.com"))
                    .andExpect(jsonPath("$.kyc.status").value("NOT_STARTED"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").value(0));
        }

        /** §5.3: the full card masks the tax ID even though it decrypts email and phone. */
        @Test
        void masksTaxIdOnTheCard() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567", ADAM_NOWAK, "PL8804170123")))
                    .andExpect(jsonPath("$.taxId").value("PL88****0123"));
        }

        /** CP-BR-03: team_id is derived from the owner's primary team, never supplied. */
        @Test
        void derivesTeamFromOwnersPrimaryTeam_CP_BR_03() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567")))
                    .andExpect(jsonPath("$.teamId").value(TEAM_RWN.toString()));
        }

        /** CP-BR-03 has no answer for an owner with no team, so the create is refused, not guessed. */
        @Test
        void refusesOwnerWithoutPrimaryTeam_CP_BR_03() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567", SOFIA_ADAMSKA)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        /** CP-BR-14, enforced by trg_clients_validate because CURRENT_DATE cannot sit in a CHECK. */
        @Test
        void rejectsClientUnder18_CP_BR_14() throws Exception {
            String minor = body("CIF-1", "a@example.com", "+48511234567", ADAM_NOWAK)
                    .replace("\"dateOfBirth\": \"1988-04-17\"", "\"dateOfBirth\": \"2015-04-17\"");
            mvc.perform(create(minor))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        @Test
        void rejectsDuplicateExternalRef_CP_BR_01() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"))).andExpect(status().isCreated());
            mvc.perform(create(body("CIF-1", "b@example.com", "+48511234568")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CLIENT_DUPLICATE_EXTERNAL_REF"));
        }

        @Test
        void rejectsDuplicateEmail_CP_BR_02() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"))).andExpect(status().isCreated());
            mvc.perform(create(body("CIF-2", "a@example.com", "+48511234568")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CLIENT_DUPLICATE_EMAIL"))
                    .andExpect(jsonPath("$.details[0].conflictingId").exists());
        }

        /** CP-BR-02 / CP-EC-03: a shared household landline is legitimate and must not be rejected. */
        @Test
        void allowsTwoClientsToShareAPhone_CP_BR_02() throws Exception {
            mvc.perform(create(body("CIF-1", "mother@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            mvc.perform(create(body("CIF-2", "son@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    class Idempotency {

        /** §4.6: the key is absent → 400 naming the header, not a generic missing-header error. */
        @Test
        void rejectsCreateWithoutIdempotencyKey_4_6() throws Exception {
            mvc.perform(post("/api/v1/clients")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("CIF-1", "a@example.com", "+48511234567")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details[0].field").value(IdempotencyService.HEADER));
        }

        @Test
        void replaysStoredResponseForTheSameKeyAndBody_4_6() throws Exception {
            String key = UUID.randomUUID().toString();
            String payload = body("CIF-1", "a@example.com", "+48511234567");
            MvcResult first = mvc.perform(create(payload, key))
                    .andExpect(status().isCreated())
                    .andReturn();

            mvc.perform(create(payload, key))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyService.REPLAYED_HEADER, "true"))
                    .andExpect(
                            jsonPath("$.id").value(jsonField(first.getResponse().getContentAsString(), "id")));

            assertThat(countClients())
                    .as("the replay must not create a second client")
                    .isEqualTo(1);
        }

        @Test
        void rejectsTheSameKeyWithADifferentBody_4_6() throws Exception {
            String key = UUID.randomUUID().toString();
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"), key))
                    .andExpect(status().isCreated());
            mvc.perform(create(body("CIF-2", "b@example.com", "+48511234568"), key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
    }

    @Nested
    class Storage {

        /** CLAUDE.md rule 8: there is no plaintext PII column, and none may be added. */
        @Test
        void schemaHasNoPlaintextPiiColumns_rule_8() {
            List<String> columns = jdbc.sql("""
                            SELECT column_name FROM information_schema.columns
                             WHERE table_schema = 'client' AND table_name = 'clients'
                               AND column_name IN ('email','phone','tax_id','address')
                            """).query(String.class).list();
            assertThat(columns).isEmpty();
        }

        @Test
        void storesCiphertextRatherThanTheValue_4_7() throws Exception {
            mvc.perform(create(body("CIF-1", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            byte[] stored = jdbc.sql("SELECT email_enc FROM client.clients")
                    .query(byte[].class)
                    .single();
            assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("anna.kowalska@example.com");
        }

        /** CLAUDE.md rule 3: the event row and the business row commit together, or not at all. */
        @Test
        void writesOutboxRowInTheSameTransaction_CP_BR_12() throws Exception {
            mvc.perform(create(body("CIF-1", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.created'")
                    .query(String.class)
                    .single();
            // jsonb reformats on storage, so compare without whitespace rather than pinning layout.
            assertThat(payload.replace(" ", "")).contains("\"action\":\"CREATE\"");
        }

        /** AR-01: no plaintext PII on the bus. The event records that email changed, not to what. */
        @Test
        void masksSensitiveValuesInTheEventPayload_AR_01() throws Exception {
            mvc.perform(create(body("CIF-1", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.created'")
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains("***MASKED***")
                    .doesNotContain("anna.kowalska@example.com")
                    .doesNotContain("+48511234567")
                    .doesNotContain("Kowalska");
            // Non-sensitive fields still carry their real values, or the audit trail is useless.
            assertThat(payload).contains("CIF-1");
        }
    }

    @Nested
    class Read {

        @Test
        void returnsCardAndAuditsTheDisclosure_CP_BR_13() throws Exception {
            String id = createdId();
            mvc.perform(get("/api/v1/clients/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                    .andExpect(jsonPath("$.email").value("anna.kowalska@example.com"))
                    .andExpect(jsonPath("$.permissions.canEdit").value(true))
                    // MANAGER holds neither client:delete nor client:merge in the §9.2.8 matrix.
                    .andExpect(jsonPath("$.permissions.canDelete").value(false))
                    .andExpect(jsonPath("$.permissions.canMerge").value(false));

            Integer disclosures = jdbc.sql(
                            "SELECT count(*) FROM client.outbox_events WHERE event_type = 'client.read_sensitive'")
                    .query(Integer.class)
                    .single();
            assertThat(disclosures).isEqualTo(1);
        }

        /** ER-01: absent and out-of-scope are indistinguishable, so existence cannot be probed. */
        @Test
        void returns404ForAnUnknownClient_ER_01() throws Exception {
            mvc.perform(get("/api/v1/clients/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        @Test
        void requiresAuthentication() throws Exception {
            mvc.perform(get("/api/v1/clients/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Lookup {

        /** CP-EC-03: mother and son on one landline both come back, and `exact` is false. */
        @Test
        void returnsEveryHouseholdMemberForAPhone_CP_EC_03() throws Exception {
            mvc.perform(create(body("CIF-1", "mother@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            mvc.perform(create(body("CIF-2", "son@example.com", "+48511234567")))
                    .andExpect(status().isCreated());

            mvc.perform(get("/api/v1/clients/lookup")
                            .param("phone", "+48511234567")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchType").value("PHONE"))
                    .andExpect(jsonPath("$.exact").value(false))
                    .andExpect(jsonPath("$.results.length()").value(2))
                    // A result list is not a card: contacts are masked (CP-BR-13).
                    .andExpect(jsonPath("$.results[0].maskedPhone").value("+48 511 *** 567"))
                    .andExpect(jsonPath("$.results[0].maskedEmail").exists())
                    .andExpect(jsonPath("$.results[0].inScope").value(true));
        }

        /** A single identifier hit is the 2-minute path: one result, `exact`, straight to the card. */
        @Test
        void reportsExactForASingleIdentifierHit_CP_US_01() throws Exception {
            mvc.perform(create(body("CIF-1", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("email", "anna.kowalska@example.com")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.exact").value(true))
                    .andExpect(jsonPath("$.results.length()").value(1));
        }

        /** CP-EC-05: case is normalized before hashing, so these are the same person. */
        @Test
        void findsTheSameRecordRegardlessOfEmailCase_CP_EC_05() throws Exception {
            mvc.perform(create(body("CIF-1", "anna.kowalska@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("q", "Anna.Kowalska@EXAMPLE.com")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.matchType").value("EMAIL"))
                    .andExpect(jsonPath("$.results.length()").value(1));
        }

        /** CP-EC-04: the national form hashes to the same value as the international one. */
        @Test
        void findsTheSameRecordFromNationalPhoneFormat_CP_EC_04() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"))).andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("q", "511 234 567")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.matchType").value("PHONE"))
                    .andExpect(jsonPath("$.results.length()").value(1));
        }

        @Test
        void detectsAnExternalRefByItsPrefix() throws Exception {
            mvc.perform(create(body("CIF-0092841", "a@example.com", "+48511234567")))
                    .andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("q", "CIF-0092841")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.matchType").value("EXTERNAL_REF"))
                    .andExpect(jsonPath("$.results.length()").value(1));
        }

        @Test
        void findsClientByNameWithTrigramSearch_CP_US_01() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"))).andExpect(status().isCreated());
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("q", "Kowalska")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.matchType").value("NAME"))
                    .andExpect(jsonPath("$.results.length()").value(1));
        }

        @Test
        void requiresExactlyOneSearchParameter() throws Exception {
            mvc.perform(get("/api/v1/clients/lookup").header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("email", "a@example.com")
                            .param("phone", "+48511234567")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsAQueryShorterThanThreeCharacters() throws Exception {
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("q", "ab")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isBadRequest());
        }

        /** §5.3: an empty result is 200 with no results, never 404. */
        @Test
        void returns200WithNoResultsWhenNothingMatches() throws Exception {
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("email", "nobody@example.com")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.results.length()").value(0));
        }

        /** A masked result list is not a disclosure, so it must not flood the audit log (CP-BR-13). */
        @Test
        void inScopeLookupEmitsNoAuditEvent_CP_BR_13() throws Exception {
            mvc.perform(create(body("CIF-1", "a@example.com", "+48511234567"))).andExpect(status().isCreated());
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(get("/api/v1/clients/lookup")
                            .param("email", "a@example.com")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk());
            assertThat(countEvents("client.read_sensitive")).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String createdId() throws Exception {
        MvcResult result = mvc.perform(create(body("CIF-0092841", "anna.kowalska@example.com", "+48511234567")))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonField(result.getResponse().getContentAsString(), "id");
    }

    private MockHttpServletRequestBuilder create(String json) {
        return create(json, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder create(String json, String idempotencyKey) {
        return post("/api/v1/clients")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                .header(IdempotencyService.HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private String body(String externalRef, String email, String phone) {
        return body(externalRef, email, phone, ADAM_NOWAK);
    }

    private String body(String externalRef, String email, String phone, UUID ownerId) {
        return body(externalRef, email, phone, ownerId, taxIdFor(externalRef));
    }

    /**
     * Tax ID is unique among non-deleted clients (CP-BR-02), so two fixture clients may not share
     * one. Phone deliberately may — that is the point of CP-EC-03.
     */
    private static String taxIdFor(String externalRef) {
        return "PL88%08d".formatted(Math.floorMod(externalRef.hashCode(), 100_000_000));
    }

    private String body(String externalRef, String email, String phone, UUID ownerId, String taxId) {
        return """
               {
                 "externalRef": "%s",
                 "firstName": "Anna",
                 "lastName": "Kowalska",
                 "dateOfBirth": "1988-04-17",
                 "email": "%s",
                 "phone": "%s",
                 "taxId": "%s",
                 "address": "ul. Prosta 51, 00-838 Warszawa",
                 "preferredChannel": "PHONE",
                 "segment": "RETAIL",
                 "ownerManagerId": "%s"
               }
               """.formatted(externalRef, email, phone, taxId, ownerId);
    }

    private int countClients() {
        return jdbc.sql("SELECT count(*) FROM client.clients")
                .query(Integer.class)
                .single();
    }
}
