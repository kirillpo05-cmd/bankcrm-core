package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

/**
 * The two questions other services ask client-service (SPEC.md §5.3 "Internal endpoints"). They
 * decide what interaction-service may do, so they get the same scrutiny as any public endpoint.
 */
@Import(MatrixAccessPolicy.Config.class)
class InternalEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Access {

        @Test
        void answersWithTheClientStateAndNoPii() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "anna.kowalska@example.com", "+48511234567");
            mvc.perform(access(id, "interaction:write", ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.clientId").value(id))
                    .andExpect(jsonPath("$.ownerManagerId").value(ADAM_NOWAK.toString()))
                    .andExpect(jsonPath("$.teamId").value(TEAM_RWN.toString()))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.kycStatus").value("NOT_STARTED"))
                    // A decision, not a card: nothing personal crosses the service boundary.
                    .andExpect(jsonPath("$.email").doesNotExist())
                    .andExpect(jsonPath("$.phone").doesNotExist())
                    .andExpect(jsonPath("$.firstName").doesNotExist());
        }

        /** CP-BR-13: an authorization check is not a disclosure, so it writes no audit row. */
        @Test
        void auditsNoDisclosure_CP_BR_13() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(access(id, "interaction:read", ADAM_NOWAK, "MANAGER")).andExpect(status().isOk());
            assertThat(countEvents("client.read_sensitive")).isZero();
        }

        /** ER-01: the calling service passes this 404 through unchanged. */
        @Test
        void outOfScopeIs404AndAudited_ER_01() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(access(id, "interaction:write", JAN_ZIELINSKI, "MANAGER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
            assertThat(countEvents("client.permission_denied")).isEqualTo(1);
        }

        /**
         * Holding the permission at no scope is 403, not 404. It depends on the caller alone, so it
         * reveals nothing about which clients exist — and a manager asking to delete an interaction
         * should be told they cannot, not that the client vanished.
         */
        @Test
        void aPermissionHeldAtNoScopeIs403() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(access(id, "interaction:delete", ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        /** 403 before existence: an unknown client and a known one give the same answer. */
        @Test
        void answersNoPermissionBeforeLookingTheClientUp() throws Exception {
            mvc.perform(access(UUID.randomUUID().toString(), "interaction:delete", ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void rejectsAPermissionOutsideTheMatrix() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(access(id, "interaction:everything", ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("permission"));
        }

        private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder access(
                String clientId, String permission, UUID actor, String role) {
            return get("/api/v1/internal/clients/{id}/access", clientId)
                    .param("permission", permission)
                    .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role));
        }
    }

    @Nested
    class Users {

        @Test
        void resolvesDisplayNamesForIds() throws Exception {
            mvc.perform(get("/api/v1/internal/users")
                            .param("ids", ADAM_NOWAK.toString(), MARTA_LEWANDOWSKA.toString())
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(JAN_ZIELINSKI)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    // Directory data, not a profile: a name for an id, nothing else.
                    .andExpect(jsonPath("$[0].email").doesNotExist());
        }

        @Test
        void unknownIdsAreSimplyAbsent() throws Exception {
            mvc.perform(get("/api/v1/internal/users")
                            .param("ids", UUID.randomUUID().toString())
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        /** There is no "list every user" here — that is user:read, a different question. */
        @Test
        void capsTheNumberOfIds() throws Exception {
            String[] ids = new String[101];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = UUID.randomUUID().toString();
            }
            mvc.perform(get("/api/v1/internal/users")
                            .param("ids", ids)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("ids"));
        }

        @Test
        void requiresAuthentication() throws Exception {
            mvc.perform(get("/api/v1/internal/users").param("ids", ADAM_NOWAK.toString()))
                    .andExpect(status().isUnauthorized());
        }
    }
}
