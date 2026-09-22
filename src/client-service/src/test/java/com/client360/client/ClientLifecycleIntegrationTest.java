package com.client360.client;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Ownership and lifecycle: {@code POST /clients/{id}/reassign} (CP-US-05) and soft delete (§4.9). */
@Import(MatrixAccessPolicy.Config.class)
class ClientLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String REASON = "Parental leave cover until 2027-01";

    @Nested
    class Reassign {

        @Test
        void supervisorMovesAClientWithinTheirTeam_CP_US_05() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, OLA_WISNIEWSKA, "SUPERVISOR", MARTA_LEWANDOWSKA, REASON))
                    .andExpect(status().isOk())
                    // The client representation is unwrapped, so its fields sit beside the count.
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.owner.id").value(MARTA_LEWANDOWSKA.toString()))
                    .andExpect(jsonPath("$.owner.fullName").value("Marta Lewandowska"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.tasksTransferred").value(0));
        }

        /** CP-BR-03: the team follows the new owner rather than being carried over or supplied. */
        @Test
        void rederivesTheTeamFromTheNewOwner_CP_BR_03() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", JAN_ZIELINSKI, REASON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.teamId").value(TEAM_RWS.toString()));
        }

        @Test
        void managerCannotReassign() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, ADAM_NOWAK, "MANAGER", MARTA_LEWANDOWSKA, REASON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.details[0].permission").value("client:reassign"));
        }

        /** A supervisor covers their team in both directions: they cannot push a client outside it. */
        @Test
        void supervisorCannotPushAClientOutsideTheirTeam_SCOPE_VIOLATION() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, OLA_WISNIEWSKA, "SUPERVISOR", JAN_ZIELINSKI, REASON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SCOPE_VIOLATION"));
        }

        @Test
        void unknownNewOwnerIs404() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", UUID.randomUUID(), REASON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }

        /** Handing a client to someone who has left is the failure CP-EC-06 exists to prevent. */
        @Test
        void deactivatedNewOwnerIs404() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", BARTOSZ_BYLY, REASON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }

        @Test
        void reassigningToTheCurrentOwnerIs422() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", ADAM_NOWAK, REASON))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("newOwnerManagerId"));
        }

        @Test
        void aReasonUnderTenCharactersIs422() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", MARTA_LEWANDOWSKA, "holiday"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("reason"));
        }

        /** The reason is why the client moved, so it belongs in the audit context. */
        @Test
        void recordsTheReasonInTheEventContext() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(reassign(id, SOFIA_ADAMSKA, "ADMIN", MARTA_LEWANDOWSKA, REASON))
                    .andExpect(status().isOk());

            String payload = jdbc.sql(
                            "SELECT payload::text FROM client.outbox_events WHERE event_type = 'client.reassigned'")
                    .query(String.class)
                    .single();
            assertThat(payload).contains(REASON).contains(MARTA_LEWANDOWSKA.toString());
        }

        /** ER-01: a supervisor of another team cannot reach the client at all. */
        @Test
        void outOfScopeClientIs404_ER_01() throws Exception {
            String id = ownedByAdam();
            mvc.perform(reassign(id, PIOTR_KOWALCZYK, "SUPERVISOR", JAN_ZIELINSKI, REASON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }
    }

    @Nested
    class SoftDelete {

        @Test
        void adminDeletesAndTheClientStopsResolving() throws Exception {
            String id = ownedByAdam();
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "Duplicate record from branch import"))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/clients/{id}", id).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /** §4.9: the row survives, because audit rows reference client_id for seven years. */
        @Test
        void keepsTheRowWithItsReason_4_9() throws Exception {
            String id = ownedByAdam();
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "Duplicate record from branch import"))
                    .andExpect(status().isNoContent());

            String reason = jdbc.sql(
                            "SELECT deletion_reason FROM client.clients WHERE id = :id AND deleted_at IS NOT NULL")
                    .param("id", UUID.fromString(id))
                    .query(String.class)
                    .single();
            assertThat(reason).isEqualTo("Duplicate record from branch import");
        }

        @Test
        void requiresIfMatch_4_8() throws Exception {
            String id = ownedByAdam();
            mvc.perform(delete("/api/v1/clients/{id}", id)
                            .param("reason", "Duplicate record from branch import")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN")))
                    .andExpect(status().isPreconditionRequired());
        }

        @Test
        void managerCannotDelete() throws Exception {
            String id = ownedByAdam();
            mvc.perform(deleteAs(id, ADAM_NOWAK, "MANAGER", 0, "Duplicate record from branch import"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        }

        @Test
        void aReasonUnderTenCharactersIs422() throws Exception {
            String id = ownedByAdam();
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "dupe"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("reason"));
        }

        /** CP-BR-09: deleting a client who still holds a live product would orphan it. */
        @Test
        void refusesWhileAnActiveProductRemains_CP_BR_09() throws Exception {
            String id = ownedByAdam();
            addActiveProduct(id);
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "Duplicate record from branch import"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"))
                    .andExpect(jsonPath("$.details[0].blocker").value("activeProducts"));
        }

        @Test
        void allowsDeletionOnceTheProductIsClosed_CP_BR_09() throws Exception {
            String id = ownedByAdam();
            addActiveProduct(id);
            jdbc.sql(
                            "UPDATE client.client_products SET status = 'CLOSED', closed_on = CURRENT_DATE WHERE client_id = :id")
                    .param("id", UUID.fromString(id))
                    .update();
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "Duplicate record from branch import"))
                    .andExpect(status().isNoContent());
        }

        @Test
        void emitsADeletedEventWithTheReason() throws Exception {
            String id = ownedByAdam();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(deleteAs(id, SOFIA_ADAMSKA, "ADMIN", 0, "Duplicate record from branch import"))
                    .andExpect(status().isNoContent());
            assertThat(countEvents("client.deleted")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String ownedByAdam() throws Exception {
        return createClient(ADAM_NOWAK, "CIF-0092841", "anna.kowalska@example.com", "+48511234567");
    }

    private void addActiveProduct(String clientId) throws Exception {
        mvc.perform(post("/api/v1/clients/{id}/products", clientId)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(SOFIA_ADAMSKA, "ADMIN"))
                        .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "CURRENT_ACCOUNT",
                                  "externalProductId": "CORE-1",
                                  "status": "ACTIVE",
                                  "currency": "PLN",
                                  "balanceMinor": 0,
                                  "openedOn": "2019-03-04"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder reassign(
            String clientId, UUID actor, String role, UUID newOwner, String reason) {
        return post("/api/v1/clients/{id}/reassign", clientId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"newOwnerManagerId": "%s", "reason": "%s", "transferOpenTasks": true}
                        """.formatted(newOwner, reason));
    }

    private MockHttpServletRequestBuilder deleteAs(
            String clientId, UUID actor, String role, int version, String reason) {
        return delete("/api/v1/clients/{id}", clientId)
                .param("reason", reason)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"");
    }
}
