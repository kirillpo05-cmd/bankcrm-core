package com.client360.client.service;

import com.client360.client.persistence.UserAdminRepository.AdminUser;
import com.client360.common.outbox.ChangedFields;
import com.client360.common.outbox.EventFactory;
import com.client360.common.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code auth.events} topic (SPEC.md §2, §9.3): {@code LOGIN_SUCCESS}, {@code LOGIN_FAILURE},
 * {@code LOGOUT} and {@code TOKEN_REFRESH}, always through the outbox (rule 3).
 *
 * <p>Two things make these events different from every other event this service emits.
 *
 * <p>The first is the actor. A login carries no token, so there is nobody on the request thread to
 * read; the actor is named explicitly from the row the credentials matched. {@code audit_log} allows
 * a null {@code actor_id} for {@code LOGIN_FAILURE} alone (§8.2.2), which is exactly the case where
 * the address matched no account.
 *
 * <p>The second is the email. A user's own address is corporate directory data and travels readably
 * as {@code actor_email} — that is what makes an audit trail legible years later (§9.2.3). An
 * address that matched <em>no</em> account is not that: it is a string an unauthenticated caller
 * typed, it may be a customer's, and it is on its way to a table kept for seven years. So it never
 * travels whole. Only its domain does, which is what makes a spray against one tenant visible
 * without recording who was sprayed at.
 */
@Component
public class AuthEvents {

    public static final String TOPIC = "auth.events";
    private static final String ENTITY = "USER";

    /**
     * The subject of a failed login against an unknown address. There is no user to point at, and
     * {@code outbox_events.aggregate_id} is {@code NOT NULL}, so this says "no such subject"
     * explicitly rather than fabricating an id that would look like somebody.
     */
    private static final UUID NO_SUBJECT = new UUID(0L, 0L);

    private static final String UNKNOWN_KEY = "unknown";

    private final EventFactory events;
    private final OutboxWriter outbox;

    public AuthEvents(EventFactory events, OutboxWriter outbox) {
        this.events = events;
        this.outbox = outbox;
    }

    /** RB-BR-05's session opened. The role is the one the audit envelope records as {@code actor.role}. */
    public void loginSucceeded(UUID userId, String email, String role, boolean mustChangePassword) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.login_success", ENTITY, userId)
                        .actor(userId, email, role)
                        .action("LOGIN_SUCCESS")
                        .context("mustChangePassword", mustChangePassword)
                        .build());
    }

    /**
     * @param userId {@code null} when the address matched no account
     * @param reason a stable machine code — {@code USER_UNKNOWN}, {@code BAD_PASSWORD},
     *     {@code ACCOUNT_LOCKED}, {@code ACCOUNT_DEACTIVATED}, {@code PASSWORD_EXPIRED} — never the
     *     message shown to the caller, which is deliberately vague
     */
    public void loginFailed(UUID userId, String attemptedEmail, String reason, boolean accountNowLocked) {
        UUID subject = userId == null ? NO_SUBJECT : userId;
        outbox.append(
                TOPIC,
                userId == null ? UNKNOWN_KEY : userId.toString(),
                events.event("auth.login_failure", ENTITY, subject)
                        // A known user is named; an unknown address is reduced to its domain.
                        .actor(userId, userId == null ? null : attemptedEmail, null)
                        .action("LOGIN_FAILURE")
                        .context("reason", reason)
                        .context("emailDomain", userId == null ? domainOf(attemptedEmail) : null)
                        .context("accountLocked", accountNowLocked ? Boolean.TRUE : null)
                        .build());
    }

    /**
     * Rotation (§9.3). Audited because it extends a session: a refresh an hour after the user went
     * home is the shape a stolen token takes, and it is only visible if each rotation is recorded.
     */
    public void tokenRefreshed(UUID userId, String email, UUID familyId) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.token_refresh", ENTITY, userId)
                        .actor(userId, email, null)
                        .action("TOKEN_REFRESH")
                        .context("familyId", familyId)
                        .build());
    }

    /**
     * RB-BR-10: a spent refresh token was presented again, so there were two holders. Recorded as a
     * {@code LOGOUT} of the whole family, because that is what it caused — and with the reason, so a
     * security review can tell it from someone clicking "sign out".
     */
    public void tokenReuseDetected(UUID userId, UUID familyId, int sessionsRevoked) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.logout", ENTITY, userId)
                        .actor(userId, null, null)
                        .action("LOGOUT")
                        .context("reason", "REFRESH_TOKEN_REUSED")
                        .context("familyId", familyId)
                        .context("sessionsRevoked", sessionsRevoked)
                        .build());
    }

    /** @param allSessions {@code POST /auth/logout-all} rather than this one device */
    public void loggedOut(UUID userId, String email, boolean allSessions, int sessionsRevoked) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.logout", ENTITY, userId)
                        .actor(userId, email, null)
                        .action("LOGOUT")
                        .context("allSessions", allSessions)
                        .context("sessionsRevoked", sessionsRevoked)
                        .build());
    }

    // -------------------------------------------------------- user administration

    /**
     * Administration events ride on {@code auth.events} because their subject is a user and the
     * topic is keyed on {@code user_id} (§2). A team event has no user to key on, so it is keyed on
     * the team — ordering only has to hold per entity, and that is what a key buys.
     *
     * <p>None of these use the {@code DELETE} action, including deactivation. RB-BR-15 says users
     * are never deleted, and recording one as deleted would make the audit trail claim something
     * the schema deliberately makes impossible. The status transition is in {@code changedFields},
     * which is what an auditor asking "who was removed from service" actually needs.
     */
    public void userCreated(UUID userId, String email, String fullName, UUID primaryTeamId) {
        ChangedFields changes = ChangedFields.create()
                .put("email", null, email)
                .put("fullName", null, fullName)
                .put("primaryTeamId", null, primaryTeamId)
                .put("status", null, "ACTIVE");
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.user_created", ENTITY, userId)
                        .action("CREATE")
                        .changes(changes)
                        .build());
    }

    /**
     * Staff directory data, so it travels readably (§9.2.3). AR-01 is about the customer's details,
     * and a colleague's name and work email are what make an audit trail legible years later.
     */
    public void userUpdated(AdminUser before, AdminUser after) {
        ChangedFields changes = ChangedFields.create()
                .put("fullName", before.fullName(), after.fullName())
                .put("primaryTeamId", before.primaryTeamId(), after.primaryTeamId())
                .put("status", before.status(), after.status());
        outbox.append(
                TOPIC,
                after.id().toString(),
                events.event("auth.user_updated", ENTITY, after.id())
                        .action("UPDATE")
                        .changes(changes)
                        .build());
    }

    /** RB-BR-11's lockout, lifted by an administrator. Worth a row: it is a security control. */
    public void userUnlocked(UUID userId, String email) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.user_unlocked", ENTITY, userId)
                        .action("UPDATE")
                        .changes(ChangedFields.create().put("lockedUntil", "SET", null))
                        .context("subjectEmail", email)
                        .build());
    }

    /** RB-BR-07. The reason is mandatory, because "why is this person gone" is asked years later. */
    public void userDeactivated(AdminUser user, String reason, UUID actorId) {
        outbox.append(
                TOPIC,
                user.id().toString(),
                events.event("auth.user_deactivated", ENTITY, user.id())
                        .action("UPDATE")
                        .changes(ChangedFields.create().put("status", "ACTIVE", "DEACTIVATED"))
                        .context("reason", reason)
                        .context("deactivatedBy", actorId)
                        .build());
    }

    public void userReactivated(AdminUser user, String reason, UUID actorId) {
        outbox.append(
                TOPIC,
                user.id().toString(),
                events.event("auth.user_reactivated", ENTITY, user.id())
                        .action("UPDATE")
                        .changes(ChangedFields.create()
                                .put("status", "DEACTIVATED", "ACTIVE")
                                .put("mustChangePassword", false, true))
                        .context("reason", reason)
                        .context("reactivatedBy", actorId)
                        .build());
    }

    /**
     * RB-BR-14: a role change is audited as {@code ROLE_CHANGE} with a mandatory reason of at least
     * ten characters, and takes effect on the subject's next token refresh.
     *
     * @param direction {@code GRANT} or {@code REVOKE}
     */
    public void roleChanged(UUID userId, String email, String roleCode, String direction, String reason, UUID actorId) {
        ChangedFields changes = "GRANT".equals(direction)
                ? ChangedFields.create().put("role", null, roleCode)
                : ChangedFields.create().put("role", roleCode, null);
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.role_changed", ENTITY, userId)
                        .action("ROLE_CHANGE")
                        .changes(changes)
                        .context("roleCode", roleCode)
                        .context("direction", direction)
                        .context("reason", reason)
                        .context("changedBy", actorId)
                        .context("subjectEmail", email)
                        .build());
    }

    // -------------------------------------------------------- team administration

    private static final String TEAM_ENTITY = "TEAM";

    public void teamCreated(UUID teamId, String code, String name) {
        outbox.append(
                TOPIC,
                teamId.toString(),
                events.event("auth.team_created", TEAM_ENTITY, teamId)
                        .action("CREATE")
                        .changes(ChangedFields.create().put("code", null, code).put("name", null, name))
                        .build());
    }

    public void teamUpdated(UUID teamId, ChangedFields changes) {
        outbox.append(
                TOPIC,
                teamId.toString(),
                events.event("auth.team_updated", TEAM_ENTITY, teamId)
                        .action("UPDATE")
                        .changes(changes)
                        .build());
    }

    /**
     * A membership change is a scope change: it decides whose clients this user can see (RB-BR-02),
     * so it is audited as carefully as a role.
     */
    public void teamMembershipChanged(UUID teamId, UUID userId, boolean joined, UUID actorId) {
        outbox.append(
                TOPIC,
                teamId.toString(),
                events.event(joined ? "auth.team_member_added" : "auth.team_member_removed", TEAM_ENTITY, teamId)
                        .action("UPDATE")
                        .changes(
                                joined
                                        ? ChangedFields.create().put("member", null, userId)
                                        : ChangedFields.create().put("member", userId, null))
                        .context("userId", userId)
                        .context("changedBy", actorId)
                        .build());
    }

    // ------------------------------------------------------------ break-glass

    /**
     * A break-glass request was raised (RB-US-05). Audited at request time, not only at approval:
     * a pattern of requests that are never approved is as interesting to a compliance review as the
     * ones that are, and a request records that someone asked to step outside their scope.
     *
     * <p>The reason travels in {@code context}. It is staff free text about why access was needed —
     * "the client needs a same-day answer on their mortgage application" — not client data, and
     * AT-BR-13 wants a break-glass read to explain itself in the log without a join.
     */
    public void accessRequested(UUID grantId, UUID userId, UUID clientId, String reason, java.time.Instant expiresAt) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.access_requested", "ACCESS_GRANT", grantId)
                        .clientId(clientId)
                        .action("ACCESS_GRANT")
                        .context("stage", "REQUESTED")
                        .context("subjectUserId", userId)
                        .context("reason", reason)
                        .context("expiresAt", expiresAt)
                        .build());
    }

    /** RB-BR-09: the second person. Both parties are on the event, because that is the control. */
    public void accessApproved(UUID grantId, UUID userId, UUID clientId, UUID approvedBy) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.access_approved", "ACCESS_GRANT", grantId)
                        .clientId(clientId)
                        .action("ACCESS_GRANT")
                        .context("stage", "APPROVED")
                        .context("subjectUserId", userId)
                        .context("approvedBy", approvedBy)
                        .build());
    }

    /**
     * A denial of a pending request, or the release of a running grant — one event, because the
     * difference is whether it had been approved, and {@code status} says which.
     */
    public void accessRevoked(UUID grantId, UUID userId, UUID clientId, UUID revokedBy, String status, int useCount) {
        outbox.append(
                TOPIC,
                userId.toString(),
                events.event("auth.access_revoked", "ACCESS_GRANT", grantId)
                        .clientId(clientId)
                        .action("ACCESS_REVOKE")
                        .context("status", status)
                        .context("subjectUserId", userId)
                        .context("revokedBy", revokedBy)
                        // How much the grant was used before it ended. A grant revoked at 40 reads
                        // is a scope problem; one revoked at 0 was probably raised by mistake.
                        .context("useCount", useCount)
                        .build());
    }

    /** {@code a.nowak@bank.example} to {@code bank.example}; {@code null} when there is no domain to take. */
    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }
}
