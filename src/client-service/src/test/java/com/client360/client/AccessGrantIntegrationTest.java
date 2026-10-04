package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Break-glass access (SPEC.md §9.3, RB-US-05, RB-BR-04, RB-BR-09).
 *
 * <p>The fixture is the scenario the feature exists for: Adam manages Retail Warsaw North, the
 * client belongs to Jan in Retail Warsaw South, and Adam needs it for one afternoon because Jan is
 * on leave. Piotr supervises RWS and is the approver; Ola supervises RWN and is not.
 *
 * <p>What is mostly under test is what the grant refuses to do. A mechanism that widens access on
 * request is only safe because of its limits, so the limits are where the tests are.
 */
class AccessGrantIntegrationTest extends AbstractIntegrationTest {

    private static final String REASON =
            "Client called the branch; owner Jan Zielinski is on leave and they need a same-day answer.";

    @Nested
    class Requesting {

        /** RB-US-05: self-service, with a reason, for one client. */
        @Test
        void aManagerRequestsAccessToAnotherTeamsClient_RB_US_05() throws Exception {
            String client = othersClient();
            request(client, ADAM_NOWAK)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$.clientId").value(client))
                    .andExpect(jsonPath("$.userId").value(ADAM_NOWAK.toString()))
                    .andExpect(jsonPath("$.expiresAt").exists())
                    .andExpect(jsonPath("$.useCount").value(0))
                    .andExpect(jsonPath("$.approvedAt").doesNotExist());
        }

        /**
         * The narrow ER-01 exception, and the second one in the system after {@code lookup}: a
         * {@code 201} here confirms that a client the caller cannot read exists. A feature for
         * requesting access to a client outside your scope cannot pretend the client is absent.
         */
        @Test
        void confirmsTheClientExistsEvenThoughItIsUnreadable_ER_01() throws Exception {
            String client = othersClient();
            // Unreadable before the request, and the request still succeeds.
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());
            request(client, ADAM_NOWAK).andExpect(status().isCreated());
            // Still unreadable: asking is not being granted.
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());
        }

        /** A client that does not exist at all is absent, exception or no exception. */
        @Test
        void aClientThatDoesNotExistIsNotFound_9_3() throws Exception {
            request(UUID.randomUUID().toString(), ADAM_NOWAK)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /**
         * §9.3: a client already in scope is {@code 422}. Not because granting would be harmful, but
         * because it would be false — the audit trail would show break-glass on a client the caller
         * could always read, and a review that cannot trust its own records is worth nothing.
         */
        @Test
        void refusesAClientAlreadyInScope_9_3() throws Exception {
            String own = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            request(own, ADAM_NOWAK)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        @Test
        void refusesAShortReason_9_3() throws Exception {
            String client = othersClient();
            mvc.perform(post("/api/v1/access-grants")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"clientId\":\"" + client + "\",\"reason\":\"urgent\",\"requestedHours\":4}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void refusesAWindowOutsideOneToEightHours_9_3() throws Exception {
            String client = othersClient();
            requestFor(client, ADAM_NOWAK, 9).andExpect(status().isBadRequest());
            requestFor(client, ADAM_NOWAK, 0).andExpect(status().isBadRequest());
        }

        /** A request nobody has answered yet is a live request, not an absent one. */
        @Test
        void refusesASecondOutstandingRequest_ACCESS_GRANT_EXISTS() throws Exception {
            String client = othersClient();
            request(client, ADAM_NOWAK).andExpect(status().isCreated());
            request(client, ADAM_NOWAK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ACCESS_GRANT_EXISTS"))
                    .andExpect(jsonPath("$.details[0].status").value("PENDING_APPROVAL"));
        }

        /**
         * Audited at request time, not only at approval: a pattern of requests that are never
         * approved tells a compliance review as much as the approved ones do.
         */
        @Test
        void auditsTheRequest_AT_US_01() throws Exception {
            jdbc.sql("DELETE FROM client.outbox_events").update();
            request(othersClient(), ADAM_NOWAK).andExpect(status().isCreated());
            assertThat(countEvents("auth.access_requested")).isEqualTo(1);
            assertThat(payloadOf("auth.access_requested")).contains("REQUESTED").contains("on leave");
        }
    }

    @Nested
    class Approving {

        /** §9.3: the client's supervisor. Scope is the whole of that — no role name is read. */
        @Test
        void theClientsSupervisorApproves_RB_US_05() throws Exception {
            String grant = pending();
            approve(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.approvedBy").value(PIOTR_KOWALCZYK.toString()))
                    .andExpect(jsonPath("$.approvedAt").exists());
        }

        /** RB-BR-09: a second person, always. */
        @Test
        void theRequesterCannotApproveTheirOwn_RB_BR_09() throws Exception {
            String grant = pending();
            // Even holding access:approve at ALL scope, which an admin does.
            jdbc.sql("""
                            INSERT INTO client.user_roles (user_id, role_id, granted_by, reason)
                            SELECT :user, r.id, :user, 'granted by the test fixture'
                              FROM client.roles r WHERE r.code = 'ADMIN'
                            ON CONFLICT (user_id, role_id) DO NOTHING
                            """).param("user", ADAM_NOWAK).update();
            approve(grant, ADAM_NOWAK)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"))
                    .andExpect(jsonPath("$.details[0].reason").value("SELF_APPROVAL_FORBIDDEN"));
        }

        /** A supervisor of a different team holds the permission, but not over this client. */
        @Test
        void anotherTeamsSupervisorCannotApprove_RB_BR_02() throws Exception {
            String grant = pending();
            approve(grant, OLA_WISNIEWSKA)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /** RB-EC-04: an admin holds it at ALL, so a request is never stuck for want of an approver. */
        @Test
        void anAdminCanAlwaysApprove_RB_EC_04() throws Exception {
            approve(pending(), SOFIA_ADAMSKA).andExpect(status().isOk());
        }

        @Test
        void cannotApproveTwice_ILLEGAL_STATE_TRANSITION() throws Exception {
            String grant = pending();
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            approve(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        /** An expired request has nothing left to approve, and says so rather than succeeding. */
        @Test
        void cannotApproveAnExpiredRequest_9_3() throws Exception {
            String grant = pending();
            age(grant);
            approve(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.details[0].status").value("EXPIRED"));
        }

        @Test
        void auditsBothParties_RB_BR_09() throws Exception {
            String grant = pending();
            jdbc.sql("DELETE FROM client.outbox_events").update();
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            assertThat(countEvents("auth.access_approved")).isEqualTo(1);
            assertThat(payloadOf("auth.access_approved"))
                    .contains(ADAM_NOWAK.toString())
                    .contains(PIOTR_KOWALCZYK.toString());
        }
    }

    /** What an approved grant actually buys, and — mostly — what it does not (RB-BR-04). */
    @Nested
    class WhatAGrantWidens {

        @Test
        void anApprovedGrantMakesTheCardReadable_RB_BR_04() throws Exception {
            String client = othersClient();
            String grant = pendingFor(client);
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());

            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            card(client, ADAM_NOWAK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(client));
        }

        /** Asking is not being granted; the approval is the whole control. */
        @Test
        void aPendingGrantWidensNothing_RB_BR_09() throws Exception {
            String client = othersClient();
            pendingFor(client);
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());
        }

        /**
         * RB-BR-04, the limit that makes the feature safe: a grant widens read and write and never
         * reassignment, deletion, merge or any audit permission.
         *
         * <p>Acted as a supervisor on purpose. A manager holds {@code client:reassign} at no scope at
         * all, so refusing them would prove nothing — the interesting caller is one who genuinely
         * holds the permission, just not over this client, because that is the caller a grant could
         * plausibly have widened.
         */
        @Test
        void aGrantNeverWidensReassignment_RB_BR_04() throws Exception {
            String client = othersClient();
            String grant = requestId(request(client, OLA_WISNIEWSKA, "SUPERVISOR"));
            approve(grant, SOFIA_ADAMSKA).andExpect(status().isOk());

            // The grant did widen the read.
            card(client, OLA_WISNIEWSKA, "SUPERVISOR").andExpect(status().isOk());
            // And did not widen client:reassign, which Ola holds at TEAM scope over her own team.
            mvc.perform(post("/api/v1/clients/{id}/reassign", client)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR"))
                            .header(
                                    com.client360.common.idempotency.IdempotencyService.HEADER,
                                    UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newOwnerManagerId\":\"" + MARTA_LEWANDOWSKA
                                    + "\",\"reason\":\"Covering while the owner is on leave for two weeks\"}"))
                    .andExpect(status().isNotFound());
        }

        /**
         * AT-BR-13: every read under a grant is counted and explains itself in the log. An auditor
         * reading a {@code READ_SENSITIVE} on a client the actor does not own should not have to go
         * looking for why it was allowed.
         */
        @Test
        void everyReadUnderAGrantIsCountedAndSelfExplaining_AT_BR_13() throws Exception {
            String client = othersClient();
            String grant = pendingFor(client);
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            jdbc.sql("DELETE FROM client.outbox_events").update();

            card(client, ADAM_NOWAK).andExpect(status().isOk());
            card(client, ADAM_NOWAK).andExpect(status().isOk());

            assertThat(useCount(grant)).isEqualTo(2);
            String payload = payloadOf("client.read_sensitive");
            assertThat(payload).contains(grant).contains("on leave");
        }

        /** An ordinary in-scope read names no grant, so the field means something when it is there. */
        @Test
        void anInScopeReadNamesNoGrant_AT_BR_13() throws Exception {
            String own = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();
            card(own, ADAM_NOWAK).andExpect(status().isOk());
            assertThat(payloadOf("client.read_sensitive")).doesNotContain("accessGrantId");
        }

        /**
         * RB-EC-03: the window closes on its own, with nobody acting and no job running. Expiry is
         * evaluated in SQL against {@code now()} (rule 7), which is what makes that true.
         */
        @Test
        void expiryEndsAccessWithNobodyActing_RB_EC_03() throws Exception {
            String client = othersClient();
            String grant = pendingFor(client);
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            card(client, ADAM_NOWAK).andExpect(status().isOk());

            age(grant);
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());
        }
    }

    @Nested
    class Revoking {

        @Test
        void revokingEndsAccessImmediately_RB_US_05() throws Exception {
            String client = othersClient();
            String grant = pendingFor(client);
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            card(client, ADAM_NOWAK).andExpect(status().isOk());

            revoke(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REVOKED"));
            card(client, ADAM_NOWAK).andExpect(status().isNotFound());
        }

        /** Declining a request and releasing a running grant are the same endpoint, different states. */
        @Test
        void decliningAPendingRequestIsRecordedAsDenied_S_RB_04() throws Exception {
            String grant = pending();
            revoke(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DENIED"));
        }

        /** Nobody should need permission to stop holding access they no longer need. */
        @Test
        void theRequesterMayReleaseTheirOwn_S_RB_04() throws Exception {
            String grant = pending();
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            revoke(grant, ADAM_NOWAK).andExpect(status().isOk());
        }

        @Test
        void cannotRevokeTwice_ILLEGAL_STATE_TRANSITION() throws Exception {
            String grant = pending();
            revoke(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            revoke(grant, PIOTR_KOWALCZYK)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        @Test
        void auditsTheRevocationWithTheUseCount_AT_BR_13() throws Exception {
            String client = othersClient();
            String grant = pendingFor(client);
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            card(client, ADAM_NOWAK).andExpect(status().isOk());
            jdbc.sql("DELETE FROM client.outbox_events").update();

            revoke(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            assertThat(countEvents("auth.access_revoked")).isEqualTo(1);
            // Asked of the JSON rather than of its rendering: jsonb::text puts a space after the
            // colon, and a test that depends on that is testing PostgreSQL's formatter.
            assertThat(context("auth.access_revoked", "useCount")).isEqualTo("1");
            assertThat(context("auth.access_revoked", "status")).isEqualTo("REVOKED");
        }
    }

    @Nested
    class Listing {

        @Test
        void aManagerSeesTheirOwnRequests_S_RB_04() throws Exception {
            pending();
            mvc.perform(get("/api/v1/access-grants?userId={id}", ADAM_NOWAK)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));
        }

        /** The approval queue, scoped to the team whose clients they supervise (RB-BR-02). */
        @Test
        void aSupervisorSeesTheirTeamsQueue_S_RB_05() throws Exception {
            pending();
            mvc.perform(get("/api/v1/access-grants")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(PIOTR_KOWALCZYK, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
            // Ola supervises the other team, so the same request is not in her queue.
            mvc.perform(get("/api/v1/access-grants")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        /** Reading someone else's grants is the approver's right, not every caller's. */
        @Test
        void aManagerCannotListAnotherUsersGrants_9_3() throws Exception {
            pending();
            mvc.perform(get("/api/v1/access-grants?userId={id}", JAN_ZIELINSKI)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        @Test
        void activeOnlyExcludesWhatIsNotRunning_9_3() throws Exception {
            String grant = pending();
            mvc.perform(get("/api/v1/access-grants?active=true")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(PIOTR_KOWALCZYK, "SUPERVISOR")))
                    .andExpect(jsonPath("$.length()").value(0));
            approve(grant, PIOTR_KOWALCZYK).andExpect(status().isOk());
            mvc.perform(get("/api/v1/access-grants?active=true")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(PIOTR_KOWALCZYK, "SUPERVISOR")))
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A client Adam cannot see: owned by Jan, in the other team. */
    private String othersClient() throws Exception {
        return createClient(JAN_ZIELINSKI, "CIF-2", "jan.client@example.com", "+48511234568");
    }

    /** A pending request by Adam for a client of the other team. */
    private String pending() throws Exception {
        return pendingFor(othersClient());
    }

    private String pendingFor(String clientId) throws Exception {
        return requestId(request(clientId, ADAM_NOWAK));
    }

    private static String requestId(ResultActions created) throws Exception {
        return jsonField(
                created.andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private ResultActions request(String clientId, UUID actor) throws Exception {
        return request(clientId, actor, "MANAGER");
    }

    private ResultActions request(String clientId, UUID actor, String role) throws Exception {
        return mvc.perform(post("/api/v1/access-grants")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"" + clientId + "\",\"reason\":\"" + REASON + "\",\"requestedHours\":4}"));
    }

    private ResultActions requestFor(String clientId, UUID actor, int hours) throws Exception {
        return mvc.perform(post("/api/v1/access-grants")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, "MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"" + clientId + "\",\"reason\":\"" + REASON + "\",\"requestedHours\":" + hours
                        + "}"));
    }

    private ResultActions approve(String grantId, UUID actor) throws Exception {
        String role = SOFIA_ADAMSKA.equals(actor) ? "ADMIN" : actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        return mvc.perform(post("/api/v1/access-grants/{id}/approve", grantId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role)));
    }

    private ResultActions revoke(String grantId, UUID actor) throws Exception {
        String role = actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        return mvc.perform(post("/api/v1/access-grants/{id}/revoke", grantId)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role)));
    }

    private ResultActions card(String clientId, UUID actor) throws Exception {
        return card(clientId, actor, "MANAGER");
    }

    private ResultActions card(String clientId, UUID actor, String role) throws Exception {
        return mvc.perform(
                get("/api/v1/clients/{id}", clientId).header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role)));
    }

    /**
     * Moves a grant's whole window into the past.
     *
     * <p>Both ends, because {@code ck_ag_window} compares them: {@code expires_at} must stay after
     * {@code requested_at} and within eight hours of it, so an expired grant is one whose request
     * was long ago — which is exactly what an expired grant is.
     */
    private void age(String grantId) {
        jdbc.sql("""
                        UPDATE client.access_grants
                           SET requested_at = now() - INTERVAL '10 hours',
                               expires_at = now() - INTERVAL '4 hours'
                         WHERE id = CAST(:id AS uuid)
                        """).param("id", grantId).update();
    }

    private int useCount(String grantId) {
        return jdbc.sql("SELECT use_count FROM client.access_grants WHERE id = CAST(:id AS uuid)")
                .param("id", grantId)
                .query(Integer.class)
                .single();
    }

    /** One {@code payload.context} field, so an assertion does not depend on jsonb's whitespace. */
    private String context(String eventType, String field) {
        return jdbc.sql("SELECT payload -> 'payload' -> 'context' ->> :field FROM client.outbox_events"
                        + " WHERE event_type = :type ORDER BY id DESC LIMIT 1")
                .param("field", field)
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private String payloadOf(String eventType) {
        return jdbc.sql("SELECT payload::text FROM client.outbox_events WHERE event_type = :type"
                        + " ORDER BY id DESC LIMIT 1")
                .param("type", eventType)
                .query(String.class)
                .single();
    }
}
