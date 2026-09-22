package com.client360.common.idempotency;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.crypto.FieldCipher;
import com.client360.common.crypto.LookupHasher;
import com.client360.common.json.CanonicalJson;
import com.client360.common.security.CurrentUsers;
import com.client360.common.web.RequestMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code Idempotency-Key} handling for every create (SPEC.md §4.6).
 *
 * <p>The key is claimed by inserting into {@code idempotency_keys} <em>inside the same
 * transaction</em> as the business change, and the response is stored in that transaction too.
 * Consequences:
 *
 * <ul>
 *   <li>A duplicate arriving while the original is in flight blocks on the unique index until the
 *       original commits, then replays it. If it would wait longer than {@code lock_timeout} it gets
 *       {@code 409 IDEMPOTENCY_IN_PROGRESS} with {@code Retry-After: 1}.
 *   <li>If the original fails, its claim rolls back with it, so a retry executes afresh. There is
 *       no "stuck in flight" state to clean up after a crash.
 * </ul>
 *
 * <p>Stored responses are AES-GCM encrypted: a create response echoes decrypted PII, and the
 * idempotency table must not become a plaintext copy of it (CLAUDE.md rule 8).
 */
@Component
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String AAD = "idempotency_keys.response_body";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final FieldCipher cipher;
    private final LookupHasher hasher;

    public IdempotencyService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            ObjectMapper objectMapper,
            FieldCipher cipher,
            LookupHasher hasher) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.hasher = hasher;
    }

    /**
     * Runs {@code action} at most once per {@code (user, endpoint, key)} within 24 h. The action
     * joins the transaction opened here, so its business writes, its outbox row and the stored
     * response commit or roll back together.
     *
     * @param rawKey the {@code Idempotency-Key} header value; absent or not a UUID is {@code 400}
     * @param payload the deserialized request body, hashed in canonical form
     */
    public ResponseEntity<Object> execute(String rawKey, Object payload, Supplier<ResponseEntity<?>> action) {
        UUID key = parseKey(rawKey);
        UUID userId = CurrentUsers.require().id();
        String endpoint = RequestMetadata.current()
                .map(RequestMetadata::endpoint)
                .orElseThrow(() -> new IllegalStateException("Idempotency requires a request context"));
        // HMAC rather than a bare digest: the payload contains PII, and a peppered hash cannot be
        // confirmed by guessing candidate inputs.
        byte[] payloadHash = hasher.hash(CanonicalJson.write(objectMapper, payload));
        try {
            return tx.execute(status -> {
                if (!claim(userId, endpoint, key, payloadHash)) {
                    return replay(userId, endpoint, key, payloadHash);
                }
                ResponseEntity<?> response = action.get();
                store(userId, endpoint, key, response);
                return widen(response);
            });
        } catch (PessimisticLockingFailureException e) {
            throw ApiException.conflict(
                            ErrorCodes.IDEMPOTENCY_IN_PROGRESS,
                            "A request with this Idempotency-Key is still being processed.")
                    .header(HttpHeaders.RETRY_AFTER, "1");
        }
    }

    /** Rows past the 24 h window are dead weight; they are not audit data and may be deleted. */
    @Scheduled(fixedDelayString = "${client360.idempotency.purge-interval:15m}")
    public void purgeExpired() {
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM idempotency_keys
                             WHERE ctid IN (SELECT ctid FROM idempotency_keys
                                             WHERE created_at < now() - INTERVAL '24 hours'
                                             LIMIT 1000)
                            """).update();
        } while (deleted == 1000);
    }

    private static UUID parseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw ApiException.validation(HEADER, "required on every create");
        }
        try {
            return UUID.fromString(rawKey.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(HEADER, "must be a client-generated UUID");
        }
    }

    /**
     * Inserts the claim, or takes over an expired one. Returns {@code false} when a live claim
     * already exists. A concurrent uncommitted claim makes this statement wait on the unique
     * index; {@code lock_timeout} bounds that wait.
     */
    private boolean claim(UUID userId, String endpoint, UUID key, byte[] payloadHash) {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        Optional<Integer> claimed = jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, endpoint, idempotency_key, payload_hash)
                        VALUES (:userId, :endpoint, :key, :hash)
                        ON CONFLICT (user_id, endpoint, idempotency_key) DO UPDATE
                           SET payload_hash      = EXCLUDED.payload_hash,
                               created_at        = now(),
                               completed_at      = NULL,
                               response_status   = NULL,
                               response_headers  = NULL,
                               response_body_enc = NULL
                         WHERE idempotency_keys.created_at < now() - INTERVAL '24 hours'
                        RETURNING 1
                        """)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("key", key)
                .param("hash", payloadHash)
                .query(Integer.class)
                .optional();
        jdbc.sql("SET LOCAL lock_timeout = DEFAULT").update();
        return claimed.isPresent();
    }

    private ResponseEntity<Object> replay(UUID userId, String endpoint, UUID key, byte[] payloadHash) {
        Stored stored = jdbc.sql("""
                        SELECT payload_hash, response_status, response_headers::text AS headers,
                               response_body_enc, key_version
                          FROM idempotency_keys
                         WHERE user_id = :userId AND endpoint = :endpoint AND idempotency_key = :key
                        """)
                .param("userId", userId)
                .param("endpoint", endpoint)
                .param("key", key)
                .query((rs, n) -> new Stored(
                        rs.getBytes("payload_hash"),
                        rs.getInt("response_status"),
                        rs.getString("headers"),
                        rs.getBytes("response_body_enc"),
                        rs.getShort("key_version")))
                .single();
        if (!MessageDigest.isEqual(stored.payloadHash(), payloadHash)) {
            throw ApiException.conflict(
                    ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body.");
        }
        log.info("replaying idempotent response for key {}", key);
        HttpHeaders headers = new HttpHeaders();
        readHeaders(stored.headersJson()).forEach(headers::set);
        headers.set(REPLAYED_HEADER, "true");
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode body =
                stored.bodyEnc() == null ? null : parse(cipher.decrypt(stored.bodyEnc(), stored.keyVersion(), AAD));
        return ResponseEntity.status(stored.status()).headers(headers).body(body);
    }

    private void store(UUID userId, String endpoint, UUID key, ResponseEntity<?> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : new String[] {HttpHeaders.LOCATION, HttpHeaders.ETAG}) {
            String value = response.getHeaders().getFirst(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        String body;
        try {
            body = response.getBody() == null ? null : objectMapper.writeValueAsString(response.getBody());
            jdbc.sql("""
                            UPDATE idempotency_keys
                               SET response_status   = :status,
                                   response_headers  = CAST(:headers AS jsonb),
                                   response_body_enc = :body,
                                   key_version       = :keyVersion,
                                   completed_at      = now()
                             WHERE user_id = :userId AND endpoint = :endpoint AND idempotency_key = :key
                            """)
                    .param("status", response.getStatusCode().value())
                    .param("headers", objectMapper.writeValueAsString(headers))
                    .param("body", cipher.encrypt(body, AAD))
                    .param("keyVersion", cipher.currentKeyVersion())
                    .param("userId", userId)
                    .param("endpoint", endpoint)
                    .param("key", key)
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response for idempotency storage", e);
        }
    }

    private Map<String, String> readHeaders(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored idempotency headers", e);
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored idempotency response", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<Object> widen(ResponseEntity<?> response) {
        return (ResponseEntity<Object>) response;
    }

    private record Stored(byte[] payloadHash, int status, String headersJson, byte[] bodyEnc, short keyVersion) {}
}
