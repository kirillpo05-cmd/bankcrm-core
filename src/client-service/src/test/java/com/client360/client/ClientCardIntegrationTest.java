package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.client.client.InteractionFeedClient;
import com.client360.client.client.RecentInteraction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * {@code GET /clients/{id}/summary} — the card's first paint (SPEC.md §5.3, CP-US-02, S-CP-02).
 *
 * <p>interaction-service is doubled. What that hides is one HTTP hop; what it leaves under test is
 * everything this service decides: authorization and the ER-01 shape, the single
 * {@code READ_SENSITIVE} disclosure, which panels degrade and — the point of the endpoint — that a
 * panel which could not load is never reported as a panel that is empty.
 */
@Import({MatrixAccessPolicy.Config.class, ClientCardIntegrationTest.Feed.class})
class ClientCardIntegrationTest extends AbstractIntegrationTest {

    /** Flipped by a test to make the timeline panel fail the way a real outage would. */
    static volatile boolean timelineAvailable = true;

    static volatile List<RecentInteraction> timeline = List.of();

    private static final RecentInteraction CALL = new RecentInteraction(
            UUID.fromString("e1000000-0000-4000-8000-000000000001"),
            "CALL",
            "OUTBOUND",
            "Mortgage rate question",
            Instant.parse("2026-09-02T09:11:00Z"),
            new RecentInteraction.Author(ADAM_NOWAK, "Adam Nowak"),
            "SUCCESSFUL");

    @BeforeEach
    void resetFeed() {
        timelineAvailable = true;
        timeline = List.of(CALL);
    }

    @Nested
    class Painting {

        /** CP-US-02: profile, products and the recent feed arrive in one round trip. */
        @Test
        void returnsProfileAndTimelineInOneCall_CP_US_02() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "anna.kowalska@example.com", "+48511234567");
            mvc.perform(summary(id, ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.client.id").value(id))
                    .andExpect(jsonPath("$.client.email").value("anna.kowalska@example.com"))
                    .andExpect(jsonPath("$.client.products").isArray())
                    .andExpect(jsonPath("$.recentInteractions.length()").value(1))
                    .andExpect(jsonPath("$.recentInteractions[0].subject").value("Mortgage rate question"))
                    .andExpect(
                            jsonPath("$.recentInteractions[0].author.fullName").value("Adam Nowak"));
        }

        /**
         * An empty feed is a statement about the client and carries no {@code degraded} entry —
         * the distinction the whole {@code degraded[]} mechanism exists to make.
         */
        @Test
        void anEmptyTimelineIsNotADegradedTimeline() throws Exception {
            timeline = List.of();
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(summary(id, ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recentInteractions.length()").value(0))
                    .andExpect(jsonPath("$.degraded", not(hasItem("recentInteractions"))));
        }
    }

    @Nested
    class Degrading {

        /** §5.3: a sub-resource that fails is named, and the card still paints. */
        @Test
        void namesTheTimelineAndStillReturnsTheProfile() throws Exception {
            timelineAvailable = false;
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(summary(id, ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.client.id").value(id))
                    .andExpect(jsonPath("$.recentInteractions.length()").value(0))
                    .andExpect(jsonPath("$.degraded", hasItem("recentInteractions")));
        }

        /**
         * Task/Reminder is v3 (§11.1). Until it exists the panel reports "not loaded" rather than
         * "nothing due", so a manager about to call never reads an absent module as an all-clear.
         */
        @Test
        void openTasksIsDegradedUntilTaskReminderShips() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(summary(id, ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.openTasks.length()").value(0))
                    .andExpect(jsonPath("$.degraded", hasItem("openTasks")));
        }

        /**
         * A caller who holds {@code client:read} but not {@code interaction:read} gets no feed and
         * no {@code degraded} entry either: the panel is not broken, it is not theirs. Reporting it
         * as degraded would invite a retry that can never succeed.
         */
        @Test
        void omitsTheTimelineForACallerWithoutInteractionRead_RB_BR_02() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(summary(id, MARTA_LEWANDOWSKA, "PROFILE_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.client.id").value(id))
                    .andExpect(jsonPath("$.recentInteractions.length()").value(0))
                    .andExpect(jsonPath("$.degraded", not(hasItem("recentInteractions"))));
        }
    }

    @Nested
    class Authorizing {

        /** ER-01: out of scope is indistinguishable from absent, even on the aggregate. */
        @Test
        void outOfScopeIsNotFound_ER_01() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            mvc.perform(summary(id, JAN_ZIELINSKI, "MANAGER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        @Test
        void missingClientIsNotFound_ER_01() throws Exception {
            mvc.perform(summary(UUID.randomUUID().toString(), ADAM_NOWAK, "MANAGER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
        }

        /**
         * CP-BR-13: the card returns decrypted PII, so it discloses exactly once — not once per
         * panel, and not again because the aggregate re-reads the client.
         */
        @Test
        void auditsOneDisclosureForTheWholeCard_CP_BR_13() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(summary(id, ADAM_NOWAK, "MANAGER")).andExpect(status().isOk());
            assertThat(countEvents("client.read_sensitive")).isEqualTo(1);
        }

        /** A refused card is a denial in the audit log, whatever the caller was told (ER-01). */
        @Test
        void auditsTheDenialWithTheRealReason_ER_01() throws Exception {
            String id = createClient(ADAM_NOWAK, "CIF-1", "a@example.com", "+48511234567");
            jdbc.sql("DELETE FROM client.outbox_events").update();
            mvc.perform(summary(id, JAN_ZIELINSKI, "MANAGER")).andExpect(status().isNotFound());
            assertThat(countEvents("client.permission_denied")).isEqualTo(1);
            assertThat(countEvents("client.read_sensitive")).isZero();
        }
    }

    private org.springframework.test.web.servlet.RequestBuilder summary(String id, UUID actor, String role) {
        return get("/api/v1/clients/{id}/summary", id).header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role));
    }

    /**
     * Stands in for interaction-service. Subclassed rather than mocked so the production
     * constructor — and with it the 800 ms sub-resource budget — still has to be satisfiable.
     */
    @TestConfiguration
    static class Feed {

        @Bean
        @Primary
        InteractionFeedClient stubFeed(RestClient.Builder builder) {
            return new InteractionFeedClient(builder, "http://localhost:0", Duration.ofMillis(800)) {
                @Override
                public List<RecentInteraction> recent(UUID clientId, int limit, String bearerToken) {
                    if (!timelineAvailable) {
                        throw new ResourceAccessException("I/O error: connect timed out");
                    }
                    return timeline;
                }
            };
        }
    }
}
