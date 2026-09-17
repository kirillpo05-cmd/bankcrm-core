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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code GET /clients} — the scoped admin list (SPEC.md §5.3, §4.5). */
@Import(MatrixAccessPolicy.Config.class)
class ClientListIntegrationTest extends AbstractIntegrationTest {

    @Nested
    class Scoping {

        /** RB-BR-02: a manager's list is their own clients, filtered in SQL rather than after. */
        @Test
        void managerSeesOnlyTheirOwnClients_RB_BR_02() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            createClient(ADAM_NOWAK, "CIF-2", "b@example.com", "+48511234562");
            createClient(MARTA_LEWANDOWSKA, "CIF-3", "c@example.com", "+48511234563");

            mvc.perform(list(ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        void supervisorSeesTheirTeam_RB_BR_02() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            createClient(MARTA_LEWANDOWSKA, "CIF-2", "b@example.com", "+48511234562");
            createClient(JAN_ZIELINSKI, "CIF-3", "c@example.com", "+48511234563");

            mvc.perform(list(OLA_WISNIEWSKA, "SUPERVISOR"))
                    .andExpect(jsonPath("$.totalElements").value(2));
            mvc.perform(list(PIOTR_KOWALCZYK, "SUPERVISOR"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void adminSeesEveryClient_RB_BR_02() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            createClient(JAN_ZIELINSKI, "CIF-2", "b@example.com", "+48511234562");

            mvc.perform(list(SOFIA_ADAMSKA, "ADMIN"))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        /**
         * A filter narrows what the caller may already see. Asking for someone else's clients must
         * not become a way to read them.
         */
        @Test
        void narrowingByOwnerCannotWidenScope_RB_BR_02() throws Exception {
            createClient(MARTA_LEWANDOWSKA, "CIF-1", "a@example.com", "+48511234561");

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("ownerId", MARTA_LEWANDOWSKA.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        /** Soft-deleted clients are gone from every read path (§4.9). */
        @Test
        void excludesSoftDeletedClients_4_9() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            jdbc.sql("""
                            UPDATE client.clients
                               SET deleted_at = now(), deletion_reason = 'Duplicate record from import'
                             WHERE id = :id
                            """).param("id", UUID.fromString(id)).update();

            mvc.perform(list(ADAM_NOWAK, "MANAGER"))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Nested
    class Paging {

        @Test
        void returnsTheOffsetEnvelope_4_5() throws Exception {
            for (int i = 1; i <= 3; i++) {
                createClient(ADAM_NOWAK, "CIF-" + i, "c" + i + "@example.com", "+4851123456" + i);
            }
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("size", "2"))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.content.length()").value(2));

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("size", "2").param("page", "1"))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        void rejectsASizeAboveTheCap_4_5() throws Exception {
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("size"));
        }

        @Test
        void rejectsANegativePage() throws Exception {
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("page"));
        }
    }

    @Nested
    class SortingAndFilters {

        /** §5.3: an unknown sort field is 400, never a silent fall back to the default. */
        @Test
        void rejectsAnUnknownSortField() throws Exception {
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("sort", "balance"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("sort"));
        }

        @Test
        void rejectsAnUnknownSortDirection() throws Exception {
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("sort", "lastName,sideways"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void sortsByLastName() throws Exception {
            rename(createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561"), "Zielińska");
            rename(createClient(ADAM_NOWAK, "CIF-2", "b@example.com", "+48511234562"), "Adamska");

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("sort", "lastName,asc"))
                    .andExpect(jsonPath("$.content[0].displayName").value("Anna Adamska"));
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("sort", "lastName,desc"))
                    .andExpect(jsonPath("$.content[0].displayName").value("Anna Zielińska"));
        }

        @Test
        void filtersBySegment() throws Exception {
            String premium = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            createClient(ADAM_NOWAK, "CIF-2", "b@example.com", "+48511234562");
            jdbc.sql("UPDATE client.clients SET segment = 'PREMIUM' WHERE id = :id")
                    .param("id", UUID.fromString(premium))
                    .update();

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("segment", "PREMIUM"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].segment").value("PREMIUM"));
        }

        @Test
        void filtersByKycStatus() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("kycStatus", "NOT_STARTED"))
                    .andExpect(jsonPath("$.totalElements").value(1));
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("kycStatus", "VERIFIED"))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        void findsClientsByNameFragment() throws Exception {
            rename(createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561"), "Wiśniewska");
            createClient(ADAM_NOWAK, "CIF-2", "b@example.com", "+48511234562");

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("q", "Wiśniewska"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        /** CP-US-04: the supervisor's "who needs re-verifying this month" view. */
        @Test
        void filtersByKycExpiringWithinDays_CP_US_04() throws Exception {
            String soon = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234561");
            String later = createClient(ADAM_NOWAK, "CIF-2", "b@example.com", "+48511234562");
            verifyUntil(soon, "10 days");
            verifyUntil(later, "200 days");

            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("kycExpiringWithinDays", "30"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void rejectsANonPositiveExpiryWindow() throws Exception {
            mvc.perform(list(ADAM_NOWAK, "MANAGER").param("kycExpiringWithinDays", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("kycExpiringWithinDays"));
        }
    }

    @Nested
    class Disclosure {

        /** CP-BR-13: a list is not a card. Contacts are masked and nothing is audited. */
        @Test
        void masksContactsAndAuditsNothing_CP_BR_13() throws Exception {
            createClient(ADAM_NOWAK, "CIF-1", "anna.kowalska@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();

            mvc.perform(list(ADAM_NOWAK, "MANAGER"))
                    .andExpect(jsonPath("$.content[0].maskedEmail").value("a****a@example.com"))
                    .andExpect(jsonPath("$.content[0].maskedPhone").value("+48 511 *** 567"))
                    .andExpect(jsonPath("$.content[0].inScope").value(true))
                    .andExpect(jsonPath("$.content[0].email").doesNotExist());

            assertThat(countEvents("client.read_sensitive")).isZero();
        }
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder list(UUID actor, String role) {
        return get("/api/v1/clients").header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role));
    }

    /** Names are plaintext columns, so a fixture can set one without going through the cipher. */
    private void rename(String clientId, String lastName) {
        jdbc.sql("UPDATE client.clients SET last_name = :lastName WHERE id = :id")
                .param("lastName", lastName)
                .param("id", UUID.fromString(clientId))
                .update();
    }

    private void verifyUntil(String clientId, String interval) {
        jdbc.sql("UPDATE client.clients SET kyc_status = 'VERIFIED', kyc_verified_at = now(),"
                        + " kyc_expires_at = now() + CAST(:interval AS interval) WHERE id = :id")
                .param("interval", interval)
                .param("id", UUID.fromString(clientId))
                .update();
    }
}
