package com.client360.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * V5 — {@code idempotency_keys}. Every constraint the migration adds is attacked here, per
 * {@code .claude/rules/db-migrations.md}: a constraint with no test that tries to violate it is an
 * untested constraint.
 */
class IdempotencyKeysSchemaTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "POST /api/v1/clients";

    /**
     * The claim is taken by {@code INSERT ... ON CONFLICT} on this triple, so the key has to be
     * unique — otherwise a duplicate request would execute twice instead of replaying.
     */
    @Test
    void rejectsASecondClaimOfTheSameKey_pk_idempotency_keys() {
        UUID key = UUID.randomUUID();
        claim(ADAM_NOWAK, ENDPOINT, key);
        assertThatThrownBy(() -> claim(ADAM_NOWAK, ENDPOINT, key)).isInstanceOf(DuplicateKeyException.class);
    }

    /** The same key from a different user, or against a different endpoint, is a different claim. */
    @Test
    void scopesTheKeyToUserAndEndpoint_pk_idempotency_keys() {
        UUID key = UUID.randomUUID();
        claim(ADAM_NOWAK, ENDPOINT, key);
        claim(MARTA_LEWANDOWSKA, ENDPOINT, key);
        claim(ADAM_NOWAK, "POST /api/v1/clients/merge", key);
        assertThat(count()).isEqualTo(3);
    }

    /**
     * A row is either in flight or complete. A {@code completed_at} with no stored status would
     * replay as an empty response, which is worse than executing again.
     */
    @Test
    void rejectsCompletionWithoutAStoredResponse_ck_idempotency_completed_pair() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO client.idempotency_keys
                                    (user_id, endpoint, idempotency_key, payload_hash, completed_at)
                                VALUES (:user, :endpoint, :key, '\\x00'::bytea, now())
                                """)
                        .param("user", ADAM_NOWAK)
                        .param("endpoint", ENDPOINT)
                        .param("key", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAStoredResponseWithNoCompletionTime_ck_idempotency_completed_pair() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO client.idempotency_keys
                                    (user_id, endpoint, idempotency_key, payload_hash, response_status)
                                VALUES (:user, :endpoint, :key, '\\x00'::bytea, 201)
                                """)
                        .param("user", ADAM_NOWAK)
                        .param("endpoint", ENDPOINT)
                        .param("key", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAStatusOutsideTheHttpRange_ck_idempotency_status_range() {
        assertThatThrownBy(() -> complete(UUID.randomUUID(), 99)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> complete(UUID.randomUUID(), 600)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsARealResponseStatus_ck_idempotency_status_range() {
        complete(UUID.randomUUID(), 201);
        assertThat(count()).isEqualTo(1);
    }

    /** The purge scans by age; without this index it degrades into a sequential scan. */
    @Test
    void indexesCreatedAtForThePurge() {
        Integer indexes = jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                         WHERE schemaname = 'client' AND tablename = 'idempotency_keys'
                           AND indexname = 'ix_idempotency_keys_created_at'
                        """).query(Integer.class).single();
        assertThat(indexes).isEqualTo(1);
    }

    /**
     * Deliberately no FK to {@code users}: these rows are a 24-hour retry cache, and a transient
     * one must not be able to block the deactivation flow of RB-BR-07.
     */
    @Test
    void hasNoForeignKeyOntoUsers() {
        Integer foreignKeys = jdbc.sql("""
                        SELECT count(*) FROM information_schema.table_constraints
                         WHERE table_schema = 'client' AND table_name = 'idempotency_keys'
                           AND constraint_type = 'FOREIGN KEY'
                        """).query(Integer.class).single();
        assertThat(foreignKeys).isZero();
    }

    private void claim(UUID user, String endpoint, UUID key) {
        jdbc.sql("""
                        INSERT INTO client.idempotency_keys (user_id, endpoint, idempotency_key, payload_hash)
                        VALUES (:user, :endpoint, :key, '\\x0102'::bytea)
                        """)
                .param("user", user)
                .param("endpoint", endpoint)
                .param("key", key)
                .update();
    }

    private void complete(UUID key, int status) {
        jdbc.sql("""
                        INSERT INTO client.idempotency_keys
                            (user_id, endpoint, idempotency_key, payload_hash, response_status, completed_at)
                        VALUES (:user, :endpoint, :key, '\\x00'::bytea, :status, now())
                        """)
                .param("user", ADAM_NOWAK)
                .param("endpoint", ENDPOINT)
                .param("key", key)
                .param("status", status)
                .update();
    }

    private int count() {
        return jdbc.sql("SELECT count(*) FROM client.idempotency_keys")
                .query(Integer.class)
                .single();
    }
}
