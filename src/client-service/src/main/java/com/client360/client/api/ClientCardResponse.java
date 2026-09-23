package com.client360.client.api;

import com.client360.client.client.RecentInteraction;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /clients/{id}/summary} — the card's first paint (SPEC.md §5.3, CP-US-02, S-CP-02).
 *
 * <p>One round trip instead of four, because the screen this backs is the project's target metric:
 * 15–20 minutes of preparation down to 2–3. The profile is the only panel whose failure fails the
 * request; every other sub-resource degrades on its own.
 *
 * <p>Deliberately not named {@code ClientSummaryResponse} — that name already belongs to a row of
 * {@code GET /clients}, which is a different and much thinner thing.
 *
 * @param degraded sub-resources that could not be loaded. The UI renders the {@code partial} state
 *     for exactly these panels rather than failing the screen. An empty panel with no entry here
 *     means the panel is genuinely empty; an entry here means nothing is known either way, which
 *     is the distinction a manager about to pick up the phone depends on.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ClientCardResponse(
        ClientResponse client,
        List<RecentInteraction> recentInteractions,
        List<OpenTask> openTasks,
        List<String> degraded) {

    /** Sub-resource names as they appear in {@link #degraded}. */
    public static final String RECENT_INTERACTIONS = "recentInteractions";

    public static final String OPEN_TASKS = "openTasks";

    /**
     * The shape §5.3 promises for the tasks panel. Task/Reminder is v3 (§11.1), so this list is
     * always empty for now and {@code openTasks} is always named in {@link #degraded} — the panel
     * reports "not loaded", never "nothing due".
     */
    public record OpenTask(
            UUID id,
            String title,
            Instant dueAt,
            String priority,
            String status,
            boolean overdue,
            ClientResponse.UserSummary assignee) {}
}
