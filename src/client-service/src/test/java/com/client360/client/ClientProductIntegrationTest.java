package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The product sub-resource (SPEC.md §5.3, CP-US-07, CP-BR-07). */
@Import(MatrixAccessPolicy.Config.class)
class ClientProductIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Reading {

        @Test
        void returnsAnEmptyArrayWhenTheClientHoldsNone() throws Exception {
            String id = ownedByAdam();
            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void showsMaskedNumberStatusAndBalance_CP_US_07() throws Exception {
            String id = ownedByAdam();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 1_250_000L);
            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].type").value("CURRENT_ACCOUNT"))
                    .andExpect(jsonPath("$[0].maskedNumber").value("PL** **** 4417"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$[0].balance.amountMinor").value(1_250_000))
                    .andExpect(jsonPath("$[0].balance.currency").value("PLN"))
                    .andExpect(jsonPath("$[0].stale").value(false));
        }

        @Test
        void filtersByStatusAndType() throws Exception {
            String id = ownedByAdam();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            addProduct(id, "MORTGAGE", "CORE-2", "SUSPENDED", 200L);

            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .param("status", "ACTIVE")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].externalProductId").doesNotExist());
            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .param("type", "MORTGAGE")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].type").value("MORTGAGE"));
        }

        /** §5.3: the card carries its products, so the panel does not need a second round trip. */
        @Test
        void productsAppearOnTheClientCard_CP_US_02() throws Exception {
            String id = ownedByAdam();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 1_250_000L);
            mvc.perform(get("/api/v1/clients/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.products.length()").value(1))
                    .andExpect(jsonPath("$.products[0].balance.amountMinor").value(1_250_000));
        }

        /** S-CP-04: data older than a day is shown as stale rather than passed off as current. */
        @Test
        void flagsDataOlderThanADayAsStale() throws Exception {
            String id = ownedByAdam();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            jdbc.sql("UPDATE client.client_products SET synced_at = now() - INTERVAL '3 days' WHERE client_id = :id")
                    .param("id", UUID.fromString(id))
                    .update();

            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(jsonPath("$[0].stale").value(true))
                    .andExpect(jsonPath("$[0].syncAgeMinutes").value(org.hamcrest.Matchers.greaterThan(4000)));
        }

        /** ER-01: a manager from another team cannot reach the client through its products. */
        @Test
        void outOfScopeClientIs404_ER_01() throws Exception {
            String id = ownedByAdam();
            mvc.perform(get("/api/v1/clients/{id}/products", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(JAN_ZIELINSKI)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }
    }

    @Nested
    class Recording {

        @Test
        void adminRecordsAProductFromCoreBanking() throws Exception {
            String id = ownedByAdam();
            mvc.perform(addRequest(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 1_250_000L))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.syncedAt").exists());
        }

        /** Products are a core-banking feed, so the manager who owns the client cannot write them. */
        @Test
        void managerCannotRecordProducts() throws Exception {
            String id = ownedByAdam();
            mvc.perform(post("/api/v1/clients/{id}/products", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                            .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productBody("CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.details[0].permission").value("product:write"));
        }

        @Test
        void rejectsADuplicateExternalProductId() throws Exception {
            String id = ownedByAdam();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(addRequest(id, "SAVINGS_ACCOUNT", "CORE-1", "ACTIVE", 200L))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODUCT_DUPLICATE_EXTERNAL_ID"));
        }

        /** CP-BR-07: a blocked client takes no new products; interactions stay writable. */
        @Test
        void refusesNewProductsForABlockedClient_CP_BR_07() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("UPDATE client.clients SET status = 'BLOCKED' WHERE id = :id")
                    .param("id", UUID.fromString(id))
                    .update();
            mvc.perform(addRequest(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        @Test
        void refusesNewProductsWhenKycIsRejected_CP_BR_07() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("""
                            UPDATE client.clients
                               SET kyc_status = 'REJECTED', kyc_rejection_reason = 'Document expired'
                             WHERE id = :id
                            """).param("id", UUID.fromString(id)).update();
            mvc.perform(addRequest(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void refusesAClosedProductWithoutAClosingDate() throws Exception {
            String id = ownedByAdam();
            mvc.perform(addRequest(id, "CURRENT_ACCOUNT", "CORE-1", "CLOSED", 0L))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("closedOn"));
        }

        /** AR-01: the balance is masked on the bus — clientId travels in the same envelope. */
        @Test
        void masksTheBalanceInTheProductEvent_AR_01() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 1_250_000L);

            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.product_added'")
                    .query(String.class)
                    .single();
            assertThat(payload)
                    .contains("***MASKED***")
                    .doesNotContain("1250000")
                    .doesNotContain("4417");
            // What changed stays readable, or the audit trail answers nothing useful.
            assertThat(payload.replace(" ", "")).contains("\"currency\":{\"new\":\"PLN\",\"old\":null}");
        }
    }

    @Nested
    class Updating {

        @Test
        void requiresIfMatch_4_8() throws Exception {
            String id = ownedByAdam();
            String productId = addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(patch("/api/v1/clients/{id}/products/{productId}", id, productId)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"balanceMinor\": 200}"))
                    .andExpect(status().isPreconditionRequired());
        }

        @Test
        void updatesTheProjectionAndBumpsVersion() throws Exception {
            String id = ownedByAdam();
            String productId = addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(patchProduct(id, productId, 0, "{\"balanceMinor\": 999}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance.amountMinor").value(999));
        }

        @Test
        void rejectsAStaleVersionAndReturnsCurrentState_4_8() throws Exception {
            String id = ownedByAdam();
            String productId = addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(patchProduct(id, productId, 0, "{\"balanceMinor\": 999}"))
                    .andExpect(status().isOk());
            mvc.perform(patchProduct(id, productId, 0, "{\"balanceMinor\": 555}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                    .andExpect(
                            jsonPath("$.details[0].current.balance.amountMinor").value(999));
        }

        /** §5.3: closing a product that still holds money would hide an open liability. */
        @Test
        void refusesToCloseAProductWithANonZeroBalance() throws Exception {
            String id = ownedByAdam();
            String productId = addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(patchProduct(id, productId, 0, """
                            {"status": "CLOSED", "closedOn": "2026-09-10", "balanceMinor": 100}
                            """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("status"));
        }

        @Test
        void closesASettledProduct() throws Exception {
            String id = ownedByAdam();
            String productId = addProduct(id, "CURRENT_ACCOUNT", "CORE-1", "ACTIVE", 100L);
            mvc.perform(patchProduct(id, productId, 0, """
                            {"status": "CLOSED", "closedOn": "2026-09-10", "balanceMinor": 0}
                            """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.closedOn").value("2026-09-10"));
        }

        @Test
        void unknownProductIs404() throws Exception {
            String id = ownedByAdam();
            mvc.perform(patchProduct(id, UUID.randomUUID().toString(), 0, "{\"balanceMinor\": 1}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ownedByAdam() throws Exception {
        return createClient(ADAM_NOWAK, "CIF-0092841", "anna.kowalska@example.com", "+48511234567");
    }

    private String addProduct(String clientId, String type, String externalId, String status, Long balance)
            throws Exception {
        MvcResult result = mvc.perform(addRequest(clientId, type, externalId, status, balance))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonField(result.getResponse().getContentAsString(), "id");
    }

    private MockHttpServletRequestBuilder addRequest(
            String clientId, String type, String externalId, String status, Long balance) {
        return post("/api/v1/clients/{id}/products", clientId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody(type, externalId, status, balance));
    }

    private MockHttpServletRequestBuilder patchProduct(String clientId, String productId, int version, String body) {
        return patch("/api/v1/clients/{id}/products/{productId}", clientId, productId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String productBody(String type, String externalId, String status, Long balance) {
        return """
                {
                  "type": "%s",
                  "externalProductId": "%s",
                  "maskedNumber": "PL** **** 4417",
                  "status": "%s",
                  "currency": "PLN",
                  "balanceMinor": %s,
                  "openedOn": "2019-03-04"
                }
                """.formatted(type, externalId, status, balance);
    }
}
