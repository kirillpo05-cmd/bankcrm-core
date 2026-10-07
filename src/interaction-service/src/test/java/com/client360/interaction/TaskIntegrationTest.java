package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Task / Reminder (SPEC.md §7.3, TR-US-01 through TR-US-06).
 *
 * <p>Most of what is under test is what a task refuses to let you do: move a deadline backwards,
 * snooze forever, complete an overdue commitment without saying what happened, or quietly cancel a
 * compliance obligation. A follow-up system is only worth having if a promise cannot disappear
 * from it, and those four rules are what make that true.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class TaskIntegrationTest extends AbstractInteractionIntegrationTest {

    private static final String REASON = "Client is abroad until the 15th and asked to be called after";

    @Nested
    class Creating {

        /** TR-US-01: the task, its reminders and its first history row in one action. */
        @Test
        void createsATaskWithRemindersAndHistory_TR_US_01() throws Exception {
            create(body(inDays(3), null, "[\"IN_APP\",\"EMAIL\"]"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("Send refinancing offer"))
                    .andExpect(jsonPath("$.status").value("OPEN"))
                    .andExpect(jsonPath("$.overdue").value(false))
                    .andExpect(jsonPath("$.snoozeCount").value(0))
                    .andExpect(jsonPath("$.snoozesRemaining").value(3))
                    .andExpect(jsonPath("$.escalationLevel").value(0))
                    // One per requested channel (TR-BR-07).
                    .andExpect(jsonPath("$.reminders.length()").value(2))
                    .andExpect(jsonPath("$.reminders[0].status").value("SCHEDULED"))
                    .andExpect(jsonPath("$.history[0].changeType").value("CREATED"))
                    .andExpect(jsonPath("$.assignee.fullName").value("Adam Nowak"));
            assertThat(countEvents("task.created")).isEqualTo(1);
        }

        /** {@code originalDueAt} is {@code dueAt} at creation, and never moves again (TR-BR-04). */
        @Test
        void freezesTheOriginalDeadline_TR_BR_04() throws Exception {
            String json = create(body(inDays(3), null, null))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(jsonField(json, "dueAt")).isEqualTo(jsonField(json, "originalDueAt"));
        }

        /** §7.3: a new task dated in the past is a mistake, whatever an existing one's state is. */
        @Test
        void refusesADeadlineInThePast_7_3() throws Exception {
            create(body(Instant.now().minus(Duration.ofHours(1)), null, null))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATED"));
        }

        @Test
        void refusesADeadlineBeyondTwoYears_7_3() throws Exception {
            create(body(inDays(800), null, null))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].maxHorizonDays").value(730));
        }

        @Test
        void refusesAShortTitle_7_3() throws Exception {
            create("""
                    {"clientId":"%s","title":"Hi","dueAt":"%s"}""".formatted(CLIENT_ID, inDays(3))).andExpect(status().isBadRequest());
        }

        /**
         * §7.3: "managers may only self-assign". Read through permissions rather than roles: a
         * manager holds {@code task:write} at {@code OWN}, so handing work to a colleague needs a
         * wider scope (rule 5).
         */
        @Test
        void aManagerCannotAssignWorkToSomeoneElse_7_3() throws Exception {
            mvc.perform(post("/api/v1/tasks")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(inDays(3), MARTA_LEWANDOWSKA, null)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        }

        /** And a supervisor can, because TEAM is wider than OWN. */
        @Test
        void aSupervisorMayAssignWithinTheirTeam_7_3() throws Exception {
            mvc.perform(post("/api/v1/tasks")
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(OLA_WISNIEWSKA, "SUPERVISOR"))
                            .header(IdempotencyService.HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(inDays(3), MARTA_LEWANDOWSKA, null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.assignee.id").value(MARTA_LEWANDOWSKA.toString()));
        }

        /** ER-01 passes through from client-service: a client out of scope is absent here too. */
        @Test
        void aClientOutOfScopeIsNotFound_ER_01() throws Exception {
            clientInScope = false;
            create(body(inDays(3), null, null)).andExpect(status().isNotFound());
        }

        /** CP-BR-08's reasoning applied to §7: a closed client has no new commitments to make. */
        @Test
        void refusesAClosedClient_CP_BR_08() throws Exception {
            clientStatus = "CLOSED";
            create(body(inDays(3), null, null))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].clientStatus").value("CLOSED"));
        }

        /**
         * TR-BR-07: a reminder whose time has already passed is recorded {@code CANCELLED} rather
         * than fired. A notification about a deadline the user already knows about is noise, and
         * the row still shows one was asked for.
         */
        @Test
        void aReminderAlreadyInThePastIsCancelledNotFired_TR_BR_07() throws Exception {
            // Due in 30 minutes with a 60-minute offset: the reminder time is already behind us.
            create("""
                    {"clientId":"%s","title":"Call back shortly","dueAt":"%s","reminderOffsetMinutes":60}""".formatted(CLIENT_ID, Instant.now().plus(Duration.ofMinutes(30))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reminders[0].status").value("CANCELLED"));
        }

        /** §4.6: a replayed create returns the first response rather than a second task. */
        @Test
        void isIdempotent_4_6() throws Exception {
            String key = UUID.randomUUID().toString();
            // The same body both times, built once. §4.6 keys on the payload as well as the key,
            // so rebuilding it would move dueAt by a few milliseconds and be refused as a reuse —
            // which is correct behaviour and a different test.
            String payload = body(inDays(3), null, null);
            String first = postWithKey(key, payload)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String replay = postWithKey(key, payload)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(jsonField(replay, "id")).isEqualTo(jsonField(first, "id"));
            assertThat(countEvents("task.created")).isEqualTo(1);
        }

        /** §4.6: the same key with a different payload is a mistake, not a replay. */
        @Test
        void refusesOneKeyForTwoDifferentPayloads_4_6() throws Exception {
            String key = UUID.randomUUID().toString();
            postWithKey(key, body(inDays(3), null, null)).andExpect(status().isCreated());
            postWithKey(key, body(inDays(4), null, null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        }
    }

    @Nested
    class Listing {

        /** TR-US-02: the default is the caller's own open work, soonest first. */
        @Test
        void defaultsToMyOpenTasksSoonestFirst_TR_US_02() throws Exception {
            createTask(inDays(5), "Later task");
            createTask(inDays(1), "Sooner task");

            list(ADAM_NOWAK, "")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].title").value("Sooner task"));
        }

        /**
         * §7.3: buckets count the whole filtered set, not the page, so the group headers stay
         * correct while paging.
         */
        @Test
        void bucketsCountTheWholeFilteredSet_7_3() throws Exception {
            createTask(inDays(1), "Today-ish");
            createTask(inDays(3), "This week");
            createTask(inDays(30), "Later");
            backdate(createTask(inDays(1), "Will be overdue"), Duration.ofDays(2));

            list(ADAM_NOWAK, "&size=1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    // Four tasks across the buckets, counted past the one-row page.
                    .andExpect(jsonPath("$.buckets.OVERDUE").value(1))
                    .andExpect(jsonPath("$.buckets.THIS_WEEK").value(2))
                    .andExpect(jsonPath("$.buckets.LATER").value(1));
        }

        /** TR-BR-02: overdue is computed, so it is a filter and not a column. */
        @Test
        void filtersOnComputedOverdue_TR_BR_02() throws Exception {
            createTask(inDays(5), "Not yet late");
            backdate(createTask(inDays(1), "Late"), Duration.ofDays(2));

            list(ADAM_NOWAK, "&overdue=true")
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].title").value("Late"))
                    .andExpect(jsonPath("$.content[0].overdue").value(true))
                    .andExpect(jsonPath("$.content[0].overdueByHours").value(org.hamcrest.Matchers.greaterThan(23.0)));
            list(ADAM_NOWAK, "&overdue=false")
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        /** TR-BR-02's other half: a completed task is never overdue, however late it was. */
        @Test
        void aCompletedTaskIsNotOverdue_TR_BR_02() throws Exception {
            String id = createTask(inDays(1), "Late but done");
            backdate(id, Duration.ofDays(2));
            complete(id, ADAM_NOWAK, "Called the client and closed it out").andExpect(status().isOk());

            list(ADAM_NOWAK, "&overdue=true")
                    .andExpect(jsonPath("$.totalElements").value(0));
            // It is "completed late" instead, which is a different metric (TR-US-08).
            mvc.perform(get("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(jsonPath("$.completedLate").value(true))
                    .andExpect(jsonPath("$.overdue").value(false));
        }

        /** A manager at OWN scope may not read a colleague's list by naming them. */
        @Test
        void aManagerCannotReadAnotherUsersTasks_7_3() throws Exception {
            list(ADAM_NOWAK, "&assigneeId=" + MARTA_LEWANDOWSKA)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SCOPE_VIOLATION"));
        }

        @Test
        void rejectsAnUnknownBucket_7_3() throws Exception {
            list(ADAM_NOWAK, "&bucket=SOMEDAY")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("bucket"));
        }

        /** The client card's Tasks tab (§7.3). */
        @Test
        void listsTasksOfOneClient_7_3() throws Exception {
            createTask(inDays(2), "On this client");
            mvc.perform(get("/api/v1/clients/{id}/tasks", CLIENT_ID)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    @Nested
    class Editing {

        @Test
        void patchesWithIfMatch_4_8() throws Exception {
            String id = createTask(inDays(3), "Original title");
            mvc.perform(patch("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Revised title\",\"priority\":\"URGENT\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Revised title"))
                    .andExpect(jsonPath("$.priority").value("URGENT"));
        }

        /**
         * TR-BR-04, the rule this module exists to protect: pulling a deadline earlier erases the
         * record of what was originally promised. The answer names the alternative.
         */
        @Test
        void refusesADeadlineMovingBackwards_TR_BR_04() throws Exception {
            String id = createTask(inDays(5), "Promised for Friday");
            mvc.perform(patch("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dueAt\":\"" + inDays(2) + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].field").value("dueAt"));
        }

        /** TR-BR-07: moving the deadline cancels the pending reminders and reschedules them. */
        @Test
        void movingTheDeadlineReschedulesReminders_TR_BR_07() throws Exception {
            String id = createTask(inDays(3), "Will move");
            mvc.perform(patch("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dueAt\":\"" + inDays(6) + "\"}"))
                    .andExpect(status().isOk())
                    // One cancelled, one freshly scheduled: a reminder for a date that no longer
                    // exists is worse than no reminder.
                    .andExpect(jsonPath("$.reminders.length()").value(2))
                    .andExpect(jsonPath("$.history[0].changeType").value("DUE_CHANGED"));
        }

        @Test
        void refusesAStaleVersion_VERSION_CONFLICT() throws Exception {
            String id = createTask(inDays(3), "Contested");
            mvc.perform(patch("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(HttpHeaders.IF_MATCH, "\"7\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Nope\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        }

        @Test
        void refusesEditingACompletedTask_7_3() throws Exception {
            String id = createTask(inDays(3), "Finished");
            complete(id, ADAM_NOWAK, null).andExpect(status().isOk());
            mvc.perform(patch("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                            .header(HttpHeaders.IF_MATCH, "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Too late\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Completing {

        /** TR-US-03: an on-time completion needs no note. */
        @Test
        void completesOnTimeWithoutANote_TR_BR_05() throws Exception {
            String id = createTask(inDays(3), "On time");
            complete(id, ADAM_NOWAK, null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.completedAt").exists())
                    .andExpect(jsonPath("$.completedBy.id").value(ADAM_NOWAK.toString()))
                    .andExpect(jsonPath("$.completedLate").value(false));
            assertThat(countEvents("task.completed")).isEqualTo(1);
        }

        /**
         * TR-BR-05: an overdue one does. The friction is deliberate and narrow — it sits exactly
         * where the information is worth having, which is when a commitment was missed.
         */
        @Test
        void refusesToCompleteAnOverdueTaskWithoutANote_TR_BR_05() throws Exception {
            String id = createTask(inDays(1), "Missed");
            backdate(id, Duration.ofDays(2));
            complete(id, ADAM_NOWAK, null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].reason").value("OVERDUE_COMPLETION_NEEDS_NOTE"));

            complete(id, ADAM_NOWAK, "Called the client on Monday; they had already signed elsewhere")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.completedLate").value(true));
        }

        @Test
        void cannotCompleteTwice_ILLEGAL_STATE_TRANSITION() throws Exception {
            String id = createTask(inDays(3), "Once");
            complete(id, ADAM_NOWAK, null).andExpect(status().isOk());
            complete(id, ADAM_NOWAK, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ILLEGAL_STATE_TRANSITION"));
        }

        /** Completing cancels the pending reminders: nobody owes the deadline any more. */
        @Test
        void completingCancelsPendingReminders_TR_BR_07() throws Exception {
            String id = createTask(inDays(3), "Done early");
            complete(id, ADAM_NOWAK, null)
                    .andExpect(jsonPath("$.reminders[0].status").value("CANCELLED"));
        }

        /** §7.3: a supervisor may complete, and the response records who actually did it. */
        @Test
        void aSupervisorMayCompleteAndIsRecorded_7_3() throws Exception {
            String id = createTask(inDays(3), "Covered");
            complete(id, OLA_WISNIEWSKA, "Handled it while Adam was out")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.completedBy.id").value(OLA_WISNIEWSKA.toString()));
        }
    }

    @Nested
    class Snoozing {

        /** TR-US-04: a legitimate delay, recorded rather than hidden. */
        @Test
        void snoozesWithAReason_TR_US_04() throws Exception {
            String id = createTask(inDays(2), "Call back");
            snooze(id, ADAM_NOWAK, inDays(5), REASON)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.snoozeCount").value(1))
                    .andExpect(jsonPath("$.snoozesRemaining").value(2))
                    .andExpect(jsonPath("$.lastSnoozeReason").value(REASON))
                    .andExpect(jsonPath("$.history[0].changeType").value("SNOOZED"));
            assertThat(countEvents("task.snoozed")).isEqualTo(1);
        }

        /** And {@code originalDueAt} does not move, which is how the delay stays visible. */
        @Test
        void aSnoozeNeverMovesTheOriginalDeadline_TR_BR_04() throws Exception {
            String id = createTask(inDays(2), "Call back");
            String json = snooze(id, ADAM_NOWAK, inDays(5), REASON)
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(jsonField(json, "dueAt")).isNotEqualTo(jsonField(json, "originalDueAt"));
        }

        /** TR-BR-06: three, then the answer is "ask for help" rather than "try again". */
        @Test
        void refusesAFourthSnooze_TR_BR_06() throws Exception {
            String id = createTask(inDays(2), "Keeps moving");
            snooze(id, ADAM_NOWAK, inDays(5), REASON).andExpect(status().isOk());
            snooze(id, ADAM_NOWAK, inDays(8), REASON).andExpect(status().isOk());
            snooze(id, ADAM_NOWAK, inDays(11), REASON).andExpect(status().isOk());
            snooze(id, ADAM_NOWAK, inDays(14), REASON)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("TASK_SNOOZE_LIMIT_REACHED"))
                    .andExpect(jsonPath("$.details[0].cap").value(3));
        }

        /** TR-BR-06: each snooze lands at most 30 days past the original promise. */
        @Test
        void refusesASnoozeBeyondThirtyDays_TR_BR_06() throws Exception {
            String id = createTask(inDays(2), "Far future");
            snooze(id, ADAM_NOWAK, inDays(40), REASON)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].maxDays").value(30));
        }

        @Test
        void refusesAShortReason_TR_BR_06() throws Exception {
            String id = createTask(inDays(2), "Why though");
            snooze(id, ADAM_NOWAK, inDays(5), "busy").andExpect(status().isBadRequest());
        }

        /**
         * §7.3: assignee only. A supervisor postponing someone else's work is a reassignment or a
         * due-date edit — different actions, different records, and conflating them would hide a
         * supervisor's decision inside a manager's.
         */
        @Test
        void onlyTheAssigneeMaySnooze_7_3() throws Exception {
            String id = createTask(inDays(2), "Not yours");
            snooze(id, OLA_WISNIEWSKA, inDays(5), REASON)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.details[0].reason").value("ASSIGNEE_ONLY"));
        }
    }

    @Nested
    class Reassigning {

        /** TR-US-06: holidays and sick leave must not create silent gaps. */
        @Test
        void reassignsWithinTheTeam_TR_US_06() throws Exception {
            String id = createTask(inDays(3), "Needs cover");
            reassign(id, OLA_WISNIEWSKA, MARTA_LEWANDOWSKA, "Adam is on leave until the 20th")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.assignee.id").value(MARTA_LEWANDOWSKA.toString()))
                    .andExpect(jsonPath("$.history[0].changeType").value("REASSIGNED"));
            assertThat(countEvents("task.reassigned")).isEqualTo(1);
        }

        /** {@code task:reassign} is supervisor and admin only in §9.2.8. */
        @Test
        void aManagerCannotReassign_7_3() throws Exception {
            String id = createTask(inDays(3), "Mine");
            reassign(id, ADAM_NOWAK, MARTA_LEWANDOWSKA, "Would rather someone else did it")
                    .andExpect(status().isForbidden());
        }

        @Test
        void refusesTheCurrentAssignee_7_3() throws Exception {
            String id = createTask(inDays(3), "Already theirs");
            reassign(id, OLA_WISNIEWSKA, ADAM_NOWAK, "Trying to reassign to the same person")
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void refusesATerminalTask_7_3() throws Exception {
            String id = createTask(inDays(3), "Finished");
            complete(id, ADAM_NOWAK, null).andExpect(status().isOk());
            reassign(id, OLA_WISNIEWSKA, MARTA_LEWANDOWSKA, "Cannot move finished work")
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Cancelling {

        /** §7.3: cancelled, never deleted — a dropped commitment is itself information. */
        @Test
        void cancelsRatherThanDeletes_7_3() throws Exception {
            String id = createTask(inDays(3), "Not needed");
            cancel(id, ADAM_NOWAK, "Client withdrew the application yesterday").andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/tasks/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancellationReason").value("Client withdrew the application yesterday"));
            assertThat(countEvents("task.cancelled")).isEqualTo(1);
        }

        @Test
        void refusesCancellingACompletedTask_7_3() throws Exception {
            String id = createTask(inDays(3), "Already done");
            complete(id, ADAM_NOWAK, null).andExpect(status().isOk());
            cancel(id, ADAM_NOWAK, "Changed my mind about this one").andExpect(status().isUnprocessableEntity());
        }

        @Test
        void refusesAShortReason_7_3() throws Exception {
            String id = createTask(inDays(3), "Why though");
            cancel(id, ADAM_NOWAK, "nope").andExpect(status().isBadRequest());
        }

        /**
         * TR-BR-10: a manager cannot cancel a KYC refresh. Cancelling one silently drops a
         * compliance obligation, so it takes someone who answers for the team rather than the
         * person the obligation is inconvenient for.
         */
        @Test
        void aManagerCannotCancelAKycRefresh_TR_BR_10() throws Exception {
            String id = createTask(inDays(14), "KYC refresh due", "KYC_REFRESH");
            cancel(id, ADAM_NOWAK, "Client says their documents are still valid")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].reason").value("KYC_REFRESH_SUPERVISOR_ONLY"));
        }

        @Test
        void aSupervisorMayCancelAKycRefresh_TR_BR_10() throws Exception {
            String id = createTask(inDays(14), "KYC refresh due", "KYC_REFRESH");
            cancel(id, OLA_WISNIEWSKA, "Refreshed in the branch; documents verified in person")
                    .andExpect(status().isNoContent());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Instant inDays(int days) {
        return Instant.now().plus(Duration.ofDays(days));
    }

    private String body(Instant dueAt, UUID assigneeId, String channels) {
        String assignee = assigneeId == null ? "" : ",\"assigneeId\":\"" + assigneeId + "\"";
        String reminders = channels == null ? "" : ",\"reminderChannels\":" + channels;
        return """
                {"clientId":"%s","title":"Send refinancing offer",
                 "description":"Written offer at 5.01%%, valid 14 days.",
                 "taskType":"DOCUMENT_REQUEST","priority":"HIGH","dueAt":"%s"%s%s}""".formatted(CLIENT_ID, dueAt, assignee, reminders);
    }

    private ResultActions create(String json) throws Exception {
        return postWithKey(UUID.randomUUID().toString(), json);
    }

    private ResultActions postWithKey(String key, String json) throws Exception {
        return mvc.perform(post("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK, "MANAGER"))
                .header(IdempotencyService.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String createTask(Instant dueAt, String title) throws Exception {
        return createTask(dueAt, title, "FOLLOW_UP");
    }

    private String createTask(Instant dueAt, String title, String taskType) throws Exception {
        String json = """
                {"clientId":"%s","title":"%s","taskType":"%s","dueAt":"%s"}""".formatted(CLIENT_ID, title, taskType, dueAt);
        return jsonField(
                create(json)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    /**
     * Moves a task's whole window into the past.
     *
     * <p>Both ends, because {@code ck_tasks_due_forward} compares them: an overdue task is one
     * whose deadline was in the past, and a deadline that moved backwards is the one thing the
     * schema refuses.
     */
    private void backdate(String taskId, Duration by) {
        // make_interval rather than binding a Duration: the driver has no interval type to send
        // it as, and the error reads as a syntax error rather than as a type mismatch.
        jdbc.sql("UPDATE interaction.tasks"
                        + "    SET due_at = due_at - make_interval(days => :days),"
                        + "        original_due_at = original_due_at - make_interval(days => :days)"
                        + "  WHERE id = CAST(:id AS uuid)")
                .param("days", (int) by.toDays())
                .param("id", taskId)
                .update();
    }

    private ResultActions list(UUID actor, String extraQuery) throws Exception {
        return mvc.perform(
                get("/api/v1/tasks?_=1" + extraQuery).header(HttpHeaders.AUTHORIZATION, bearerFor(actor, "MANAGER")));
    }

    private ResultActions complete(String id, UUID actor, String note) throws Exception {
        String role = actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        String body = note == null ? "{}" : "{\"note\":\"" + note + "\"}";
        return mvc.perform(post("/api/v1/tasks/{id}/complete", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions snooze(String id, UUID actor, Instant newDueAt, String reason) throws Exception {
        String role = actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        return mvc.perform(post("/api/v1/tasks/{id}/snooze", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDueAt\":\"" + newDueAt + "\",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions reassign(String id, UUID actor, UUID newAssignee, String reason) throws Exception {
        String role = actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        return mvc.perform(post("/api/v1/tasks/{id}/reassign", id)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newAssigneeId\":\"" + newAssignee + "\",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions cancel(String id, UUID actor, String reason) throws Exception {
        String role = actor.equals(ADAM_NOWAK) ? "MANAGER" : "SUPERVISOR";
        int version = jdbc.sql("SELECT version FROM interaction.tasks WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query(Integer.class)
                .single();
        return mvc.perform(delete("/api/v1/tasks/{id}", id)
                .param("reason", reason)
                .header(HttpHeaders.AUTHORIZATION, bearerFor(actor, role))
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\""));
    }

    /**
     * Deliberately crude: the tests assert on the wire shape, not on a deserialized model.
     *
     * <p>It does fail loudly on a missing field, though. The version in the other suites adds the
     * marker length to -1 and returns whatever happens to be at index 5 — which is how an error
     * envelope quietly became the string "estamp" in an assertion about an id.
     */
    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int at = json.indexOf(marker);
        assertThat(at).withFailMessage("no \"%s\" in response: %s", field, json).isNotNegative();
        int start = at + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
