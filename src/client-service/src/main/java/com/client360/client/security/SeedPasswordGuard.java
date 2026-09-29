package com.client360.client.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to run with a local seed password in the database.
 *
 * <p>{@code db/seed/local/seed_local.sql} stores {@code {noop}local-dev-only} so that a developer
 * can log in the minute the stack is up, and left alone that is a set of published credentials for
 * nine staff accounts. The seed file has said since it was written that the application must refuse
 * to start with any {@code {noop}} hash outside the {@code local} profile; this is that guard, and
 * it belongs with the code that can now actually verify a password.
 *
 * <p>It fails the application rather than logging a warning. A warning in a startup log is not a
 * control — this is the difference between a demo stack and an authentication system.
 */
@Component
public class SeedPasswordGuard {

    private static final Logger log = LoggerFactory.getLogger(SeedPasswordGuard.class);

    /** Development profiles where a readable seed password is the point. */
    private static final java.util.Set<String> PERMITTED = java.util.Set.of("local", "test");

    private final JdbcClient jdbc;
    private final Environment environment;

    public SeedPasswordGuard(JdbcClient jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    /**
     * Checked after startup rather than in a constructor so the failure names the problem instead of
     * surfacing as a bean creation error, and so Flyway has certainly run.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        int seeded = jdbc.sql("SELECT count(*) FROM users WHERE password_hash LIKE '{noop}%'")
                .query(Integer.class)
                .single();
        if (seeded == 0) {
            return;
        }
        boolean permitted =
                java.util.Arrays.stream(environment.getActiveProfiles()).anyMatch(PERMITTED::contains);
        if (!permitted) {
            // No email, employee number or count-by-name: the message goes to an operational log.
            throw new IllegalStateException("Refusing to serve: " + seeded
                    + " user(s) still hold a {noop} seed password. Active profiles: "
                    + String.join(",", environment.getActiveProfiles())
                    + ". Reset them before starting outside the local profile "
                    + "(db/seed/local/seed_local.sql explains why).");
        }
        log.warn(
                "{} user(s) hold a {{noop}} development password. Permitted by the active profile; "
                        + "never permitted outside it.",
                seeded);
    }
}
