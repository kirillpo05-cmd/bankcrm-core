package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /clients/merge} (SPEC.md §5.3, CP-US-06, CP-BR-10).
 *
 * <p>A merge is irreversible through the API (Q-08), so the interesting cases are the ones it
 * refuses: what it will not do is the whole safety of the feature.
 */
@Import(MatrixAccessPolicy.Config.class)
class ClientMergeIntegrationTest extends AbstractIntegrationTest {

    private static final String REASON = "Same person, duplicate created by the branch import on 2026-08-30";

    @Nested
    class Merging {

        @Test
        void theLoserPointsAtTheSurvivorAndIsGoneFromReads_CP_BR_10() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            merge(survivor, loser)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.survivor.id").value(survivor))
                    .andExpect(jsonPath("$.mergedId").value(loser));

            // Every later read of the loser is a 410 pointing at the survivor.
            mvc.perform(get("/api/v1/clients/{id}", loser).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value("CLIENT_MERGED"))
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/clients/" + survivor));
        }

        /** The survivor keeps its own id and external_ref; only contested contact fields move. */
        @Test
        void theSurvivorKeepsItsIdentity_CP_BR_10() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            merge(survivor, loser)
                    .andExpect(jsonPath("$.survivor.externalRef").value("CIF-1"))
                    .andExpect(jsonPath("$.survivor.email").value("anna@example.com"));
        }

        /** CP-US-06's fieldResolution: the duplicate may hold the better number. */
        @Test
        void theSurvivorMayAdoptTheLosersContactDetails_CP_US_06() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            merge(survivor, loser, "\"fieldResolution\":{\"PHONE\":\"MERGED\",\"EMAIL\":\"MERGED\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.survivor.phone").value("+48511234568"))
                    .andExpect(jsonPath("$.survivor.email").value("anna.dup@example.com"));
        }

        /**
         * Email is unique among non-deleted clients (CP-BR-02), so adopting the loser's only works
         * because the loser is soft-deleted first. The order is the whole reason this passes.
         */
        @Test
        void adoptingAUniqueFieldDoesNotCollideWithTheLoser_CP_BR_02() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            merge(survivor, loser, "\"fieldResolution\":{\"EMAIL\":\"MERGED\"}").andExpect(status().isOk());
            assertThat(countEvents("client.merged")).isEqualTo(1);
        }

        /** Interactions belong to another service, so the response names them instead of guessing. */
        @Test
        void interactionsAreReportedAsFollowingAsynchronously() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            merge(survivor, loser)
                    .andExpect(jsonPath("$.pending[0]").value("interactions"))
                    .andExpect(jsonPath("$.moved.products").value(0));
        }
    }

    @Nested
    class Refusing {

        @Test
        void aManagerCannotMerge_ROLE_REQUIRED() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            mvc.perform(mergeRequest(survivor, loser, "", ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        }

        @Test
        void aRecordCannotBeMergedIntoItself() throws Exception {
            String client = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            merge(client, client)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("mergedId"));
        }

        /** Irreversible means the reason is the only account of why; twenty characters is the floor. */
        @Test
        void aShortReasonIsRejected_CP_BR_10() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");

            mvc.perform(post("/api/v1/clients/merge")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "ADMIN"))
                            .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"survivorId":"%s","mergedId":"%s","reason":"dupe"}""".formatted(survivor, loser)))
                    .andExpect(status().isBadRequest());
        }

        /** A record that was already merged has stopped being a record; there is nothing to give. */
        @Test
        void anAlreadyMergedRecordCannotBeMergedAgain_ILLEGAL_STATE_TRANSITION() throws Exception {
            String survivor = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String loser = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");
            String third = createClient(ADAM_NOWAK, "CIF-3", "anna.third@example.com", "+48511234569");

            merge(survivor, loser).andExpect(status().isOk());
            merge(third, loser)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        /** CP-EC-09: chains are allowed, so a survivor may itself be merged onward. */
        @Test
        void aSurvivorMayBeMergedOnward_CP_EC_09() throws Exception {
            String first = createClient(ADAM_NOWAK, "CIF-1", "anna@example.com", "+48511234567");
            String second = createClient(ADAM_NOWAK, "CIF-2", "anna.dup@example.com", "+48511234568");
            String third = createClient(ADAM_NOWAK, "CIF-3", "anna.third@example.com", "+48511234569");

            merge(second, first).andExpect(status().isOk());
            merge(third, second).andExpect(status().isOk());

            // The 410 follows the chain to the end, not one hop.
            mvc.perform(get("/api/v1/clients/{id}", first).header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isGone())
                    .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/clients/" + third));
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions merge(String survivor, String loser) throws Exception {
        return mvc.perform(mergeRequest(survivor, loser, "", OLA_WISNIEWSKA, "ADMIN"));
    }

    private ResultActions merge(String survivor, String loser, String extraJson) throws Exception {
        return mvc.perform(mergeRequest(survivor, loser, extraJson, OLA_WISNIEWSKA, "ADMIN"));
    }

    private org.springframework.test.web.servlet.RequestBuilder mergeRequest(
            String survivor, String loser, String extraJson, UUID actor, String role) {
        String extra = extraJson.isEmpty() ? "" : "," + extraJson;
        return post("/api/v1/clients/merge")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"survivorId":"%s","mergedId":"%s","reason":"%s"%s}""".formatted(survivor, loser, REASON, extra));
    }
}
