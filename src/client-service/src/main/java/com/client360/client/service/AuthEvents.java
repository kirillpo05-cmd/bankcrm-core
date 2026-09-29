package com.client360.client.service;

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

    /** {@code a.nowak@bank.example} to {@code bank.example}; {@code null} when there is no domain to take. */
    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at < 0 || at == email.length() - 1 ? null : email.substring(at + 1);
    }
}
