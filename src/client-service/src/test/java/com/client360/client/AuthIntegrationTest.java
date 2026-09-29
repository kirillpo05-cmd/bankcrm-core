package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /auth/login}, {@code /auth/refresh}, {@code /auth/logout} and {@code /auth/logout-all}
 * (SPEC.md §9.3, RB-BR-10, RB-BR-11, RB-BR-12).
 *
 * <p>Until this endpoint existed every test in this suite minted its own token, and the service
 * trusted anything correctly signed. What is under test here is the other half of that: that a
 * session now begins with a proved password, ends when someone says so, and cannot be used to find
 * out which email addresses belong to staff.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    private static final String ADAM_EMAIL = "a.nowak@bank.example";

    @Nested
    class LoggingIn {

        /** RB-US-01: the token a login returns is a token the API accepts. Nothing here is stubbed. */
        @Test
        void issuesASessionThatTheApiThenAccepts_RB_US_01() throws Exception {
            grantRole(ADAM_NOWAK, "MANAGER");
            MvcResult result = login(ADAM_EMAIL, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.refreshExpiresIn").value(28800))
                    .andExpect(jsonPath("$.user.id").value(ADAM_NOWAK.toString()))
                    .andExpect(jsonPath("$.user.fullName").value("Adam Nowak"))
                    .andExpect(jsonPath("$.user.roles[0]").value("MANAGER"))
                    .andExpect(jsonPath("$.user.primaryTeam.name").value("Retail Warsaw North"))
                    .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            mvc.perform(get("/api/v1/clients")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jsonField(body, "accessToken")))
                    .andExpect(status().isOk());
        }

        /** A token must not be cached by a proxy, or left in a browser's disk cache. */
        @Test
        void forbidsCachingTheResponse() throws Exception {
            login(ADAM_EMAIL, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }

        /**
         * RB-BR-05: the token carries the caller's permissions with scopes, which is how a service
         * holding no RBAC tables of its own — interaction-service, audit-service — can authorize
         * without a call per request.
         */
        @Test
        void theTokenCarriesPermissionsWithScopes_RB_BR_05() throws Exception {
            grantRole(ADAM_NOWAK, "MANAGER");
            Map<String, Object> claims = claimsOf(accessTokenFor(ADAM_EMAIL));

            @SuppressWarnings("unchecked")
            Map<String, String> permissions = (Map<String, String>) claims.get("permissions");
            assertThat(permissions).containsEntry("client:read", "OWN").containsEntry("client:write", "OWN");
            // A manager holds no audit permission at any scope (AT-BR-11), so none may appear here.
            assertThat(permissions).doesNotContainKey("audit:read");
            assertThat(claims.get("teamIds")).isEqualTo(List.of(TEAM_RWN.toString()));
            assertThat(claims.get("grantIds")).isEqualTo(List.of());
            assertThat(claims.get("email")).isEqualTo(ADAM_EMAIL);
        }

        /**
         * §9.3: one code for an unknown address and for a wrong password. Two codes would make the
         * endpoint answer "is this an employee here?" to anyone who asked.
         */
        @Test
        void oneCodeForAnUnknownAddressAndAWrongPassword_9_3() throws Exception {
            login("nobody@bank.example", PASSWORD)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            login(ADAM_EMAIL, "wrong-password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        /** RB-BR-11: five consecutive failures, then fifteen minutes. */
        @Test
        void locksTheAccountAfterFiveFailures_RB_BR_11() throws Exception {
            for (int i = 0; i < 5; i++) {
                login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            }
            login(ADAM_EMAIL, PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                    .andExpect(jsonPath("$.details[0].lockedUntil").exists());
        }

        /**
         * The lock is never reported to a caller who did not know the password.
         *
         * <p>§9.3 lists {@code ACCOUNT_LOCKED} as a login outcome, and it is — but if five wrong
         * guesses turned a {@code 401} into a {@code 403}, the endpoint would answer "this address is
         * a real account" to anyone willing to spend five requests, which is precisely what the
         * single {@code INVALID_CREDENTIALS} code exists to prevent. So account state is reported
         * only once the password is known to be right. SPEC.md §9.3 records this refinement.
         */
        @Test
        void aLockIsNotAnAccountExistenceOracle_9_3() throws Exception {
            for (int i = 0; i < 5; i++) {
                login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            }
            // Locked, but this caller still has not proved they know anything.
            login(ADAM_EMAIL, "still-wrong")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
            // An address that does not exist can never be locked, so the two are indistinguishable.
            login("nobody@bank.example", "still-wrong")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        /** RB-BR-11: the counter is consecutive failures, so a success clears it. */
        @Test
        void aSuccessResetsTheFailureCounter_RB_BR_11() throws Exception {
            for (int i = 0; i < 4; i++) {
                login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            }
            login(ADAM_EMAIL, PASSWORD).andExpect(status().isOk());
            assertThat(failedCount(ADAM_NOWAK)).isZero();

            // Four more must therefore not be enough to lock.
            for (int i = 0; i < 4; i++) {
                login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            }
            login(ADAM_EMAIL, PASSWORD).andExpect(status().isOk());
        }

        @Test
        void refusesADeactivatedAccount_RB_BR_15() throws Exception {
            login("b.byly@bank.example", PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));
        }

        /** RB-BR-12: 90-day maximum age, evaluated in SQL against {@code now()} (rule 7). */
        @Test
        void refusesAPasswordPastNinetyDays_RB_BR_12() throws Exception {
            jdbc.sql("UPDATE client.users SET password_changed_at = now() - INTERVAL '91 days' WHERE id = :id")
                    .param("id", ADAM_NOWAK)
                    .update();
            login(ADAM_EMAIL, PASSWORD)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PASSWORD_EXPIRED"))
                    .andExpect(jsonPath("$.details[0].changePasswordUrl").exists());
        }

        /**
         * {@code must_change_password} is a flag on a successful login, not a refusal: the seeded
         * users all carry it, and a system that refused them would have no way in at all.
         */
        @Test
        void reportsMustChangePasswordWithoutRefusing() throws Exception {
            jdbc.sql("UPDATE client.users SET must_change_password = true WHERE id = :id")
                    .param("id", ADAM_NOWAK)
                    .update();
            login(ADAM_EMAIL, PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.mustChangePassword").value(true));
        }

        /**
         * RB-BR-11's second limit, per IP and independent of the account. Counting per account alone
         * lets an attacker spray one password across many addresses, and the spray never trips a
         * single account's counter.
         */
        @Test
        void throttlesPerIpIndependentlyOfTheAccount_RB_BR_11() throws Exception {
            for (int i = 0; i < 10; i++) {
                login("nobody" + i + "@bank.example", "wrong-password").andExpect(status().isUnauthorized());
            }
            login("nobody11@bank.example", "wrong-password")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
            // No account was ever locked; ten addresses were tried once each.
            assertThat(failedCount(ADAM_NOWAK)).isZero();
        }

        /** The attempt is recorded even though the request failed (§9.2.10). */
        @Test
        void recordsEveryAttemptWhicheverWayItEnded() throws Exception {
            login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            login(ADAM_EMAIL, PASSWORD).andExpect(status().isOk());
            assertThat(attempts(ADAM_EMAIL, false)).isEqualTo(1);
            assertThat(attempts(ADAM_EMAIL, true)).isEqualTo(1);
        }
    }

    @Nested
    class Auditing {

        /** Both outcomes reach {@code auth.events} through the outbox (rule 3). */
        @Test
        void auditsSuccessAndFailure_AT_US_01() throws Exception {
            login(ADAM_EMAIL, PASSWORD).andExpect(status().isOk());
            login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            assertThat(countEvents("auth.login_success")).isEqualTo(1);
            assertThat(countEvents("auth.login_failure")).isEqualTo(1);
            assertThat(topicOf("auth.login_success")).isEqualTo("auth.events");
        }

        /**
         * AR-01, applied to the one string a stranger controls. A known employee's address travels
         * readably — it is corporate directory data and the whole point of {@code actor_email}
         * (§9.2.3) — but an address that matched no account is somebody's personal email on its way
         * into a table kept for seven years, so only its domain goes.
         */
        @Test
        void neverPutsAnUnknownAddressOnTheBus_AR_01() throws Exception {
            login("someone.private@gmail.example", "wrong-password").andExpect(status().isUnauthorized());
            String payload = payloadOf("auth.login_failure");
            assertThat(payload).doesNotContain("someone.private");
            assertThat(payload).contains("gmail.example");
            assertThat(payload).contains("USER_UNKNOWN");
        }

        /** A known user is named, because that is what makes the audit trail legible (§9.2.3). */
        @Test
        void namesAKnownEmployee_9_2_3() throws Exception {
            login(ADAM_EMAIL, PASSWORD).andExpect(status().isOk());
            assertThat(payloadOf("auth.login_success")).contains(ADAM_EMAIL);
        }

        /** The lock is a security event, so the event says so rather than leaving it to be inferred. */
        @Test
        void recordsTheLockOnTheFailureThatCausedIt_RB_BR_11() throws Exception {
            for (int i = 0; i < 5; i++) {
                login(ADAM_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
            }
            assertThat(countEvents("auth.login_failure")).isEqualTo(5);
            assertThat(jdbc.sql("SELECT count(*) FROM client.outbox_events"
                                    + " WHERE event_type = 'auth.login_failure'"
                                    + "   AND payload -> 'payload' -> 'context' ->> 'accountLocked' = 'true'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }
    }

    @Nested
    class Refreshing {

        /** §9.3: a refresh returns a new access token <em>and</em> a new refresh token. */
        @Test
        void rotatesTheRefreshTokenOnEveryUse_9_3() throws Exception {
            String first = refreshTokenFor(ADAM_EMAIL);
            String second = rotatedTokenOf(refresh(first).andExpect(status().isOk()));
            assertThat(second).isNotEqualTo(first);

            String third = rotatedTokenOf(refresh(second).andExpect(status().isOk()));
            assertThat(third).isNotIn(first, second);
        }

        /**
         * RB-BR-10, the reason rotation is worth the bookkeeping: two holders of one token means one
         * of them stole it, and there is no way to tell which. So the whole family goes — including
         * the token that is currently valid, which is what actually stops the thief.
         */
        @Test
        void aReusedTokenRevokesTheWholeFamily_RB_BR_10() throws Exception {
            String first = refreshTokenFor(ADAM_EMAIL);
            String rotated = rotatedTokenOf(refresh(first).andExpect(status().isOk()));

            refresh(first)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

            // The live token is gone too. Without this the thief keeps the session they rotated.
            refresh(rotated)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
            assertThat(countEvents("auth.logout")).isEqualTo(1);
        }

        @Test
        void refusesAnUnknownToken_9_3() throws Exception {
            refresh("not-a-token-anyone-issued")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        }

        /** Expiry is the database's judgement, not the JVM's (rule 7). */
        @Test
        void refusesAnExpiredToken_9_3() throws Exception {
            String token = refreshTokenFor(ADAM_EMAIL);
            jdbc.sql("UPDATE client.refresh_tokens SET issued_at = now() - INTERVAL '9 hours',"
                            + " expires_at = now() - INTERVAL '1 hour'")
                    .update();
            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
        }

        @Test
        void refusesAUserDeactivatedSinceIssuance_9_3() throws Exception {
            String token = refreshTokenFor(ADAM_EMAIL);
            jdbc.sql("UPDATE client.users SET status = 'DEACTIVATED', deactivated_at = now() WHERE id = :id")
                    .param("id", ADAM_NOWAK)
                    .update();
            refresh(token)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));
        }

        /**
         * RB-BR-14: a role change takes effect on the next refresh. The access token is a 15-minute
         * snapshot, and this is the moment it is retaken.
         */
        @Test
        void picksUpARoleChangeOnRefresh_RB_BR_14() throws Exception {
            String token = refreshTokenFor(ADAM_EMAIL);
            ResultActions before = refresh(token).andExpect(status().isOk());
            assertThat(permissionsIn(accessTokenOf(before))).doesNotContainKey("client:read");

            grantRole(ADAM_NOWAK, "MANAGER");
            // The rotated token, never the one just spent — presenting that twice is RB-BR-10's
            // theft signal, and it would end the session instead of renewing it.
            ResultActions after = refresh(rotatedTokenOf(before)).andExpect(status().isOk());
            assertThat(permissionsIn(accessTokenOf(after))).containsEntry("client:read", "OWN");
        }
    }

    @Nested
    class LoggingOut {

        @Test
        void revokesThePresentedToken_9_3() throws Exception {
            String token = refreshTokenFor(ADAM_EMAIL);
            mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"" + token + "\"}"))
                    .andExpect(status().isNoContent());
            refresh(token)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
            assertThat(countEvents("auth.logout")).isEqualTo(1);
        }

        /**
         * Idempotent, and silent about what it did not find. An error for an unknown token would let
         * an unauthenticated caller test whether a token string is live.
         */
        @Test
        void isIdempotentAndRevealsNothing_9_3() throws Exception {
            mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"never-issued\"}"))
                    .andExpect(status().isNoContent());
            String token = refreshTokenFor(ADAM_EMAIL);
            mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"" + token + "\"}"))
                    .andExpect(status().isNoContent());
            mvc.perform(json(post("/api/v1/auth/logout"), "{\"refreshToken\":\"" + token + "\"}"))
                    .andExpect(status().isNoContent());
            assertThat(countEvents("auth.logout")).isEqualTo(1);
        }

        /** RB-US-04: every device, which is what an admin needs when a laptop goes missing. */
        @Test
        void logoutAllEndsEverySession_RB_US_04() throws Exception {
            String laptop = refreshTokenFor(ADAM_EMAIL);
            String phone = refreshTokenFor(ADAM_EMAIL);
            String access = accessTokenFor(ADAM_EMAIL);

            mvc.perform(post("/api/v1/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                    .andExpect(status().isNoContent());

            refresh(laptop).andExpect(status().isUnauthorized());
            refresh(phone).andExpect(status().isUnauthorized());
        }

        /** Unlike {@code /auth/logout}, this one acts on the caller, so it has to know who they are. */
        @Test
        void logoutAllRequiresAuthentication_9_3() throws Exception {
            mvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(
                json(post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String accessTokenFor(String email) throws Exception {
        return jsonField(
                login(email, PASSWORD)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "accessToken");
    }

    private String refreshTokenFor(String email) throws Exception {
        return jsonField(
                login(email, PASSWORD)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "refreshToken");
    }

    private static String accessTokenOf(ResultActions response) throws Exception {
        return jsonField(response.andReturn().getResponse().getContentAsString(), "accessToken");
    }

    private static String rotatedTokenOf(ResultActions response) throws Exception {
        return jsonField(response.andReturn().getResponse().getContentAsString(), "refreshToken");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> permissionsIn(String accessToken) {
        return (Map<String, String>) claimsOf(accessToken).get("permissions");
    }

    /** Parsed rather than trusted: the claims are a contract two other services read. */
    private static Map<String, Object> claimsOf(String accessToken) {
        try {
            return SignedJWT.parse(accessToken).getJWTClaimsSet().getClaims();
        } catch (java.text.ParseException e) {
            throw new AssertionError("The service issued a token that cannot be parsed", e);
        }
    }

    /** A role, granted for real, without going through {@code bearerFor}'s token. */
    private void grantRole(UUID userId, String roleCode) {
        jdbc.sql("""
                        INSERT INTO client.user_roles (user_id, role_id, granted_by, reason)
                        SELECT :userId, r.id, :userId, 'granted by the test fixture'
                          FROM client.roles r WHERE r.code = :role
                        ON CONFLICT (user_id, role_id) DO NOTHING
                        """).param("userId", userId).param("role", roleCode).update();
    }

    private int failedCount(UUID userId) {
        return jdbc.sql("SELECT failed_login_count FROM client.users WHERE id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
    }

    private int attempts(String email, boolean success) {
        return jdbc.sql("SELECT count(*) FROM client.login_attempts WHERE email = :email AND success = :success")
                .param("email", email)
                .param("success", success)
                .query(Integer.class)
                .single();
    }

    private String payloadOf(String eventType) {
        return jdbc.sql("SELECT payload::text FROM client.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private String topicOf(String eventType) {
        return jdbc.sql("SELECT topic FROM client.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(String.class)
                .single();
    }
}
