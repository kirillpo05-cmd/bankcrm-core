package com.client360.audit.chain;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The tamper-evidence chain of SPEC.md §8.2.4.
 *
 * <p>{@code row_hash} covers {@code prev_hash}, so altering or removing any row breaks every
 * subsequent hash in its chain. One chain per Kafka partition ({@code chain_id}), not one global
 * chain: a global chain would force a single-threaded consumer and make audit the throughput
 * ceiling of the whole system.
 *
 * <p><strong>Every byte of the input is pinned.</strong> A hash chain whose serialization can drift
 * is a chain that reports tampering when a library is upgraded, and one that nobody trusts after
 * the first false alarm. So the timestamp format matches PostgreSQL's
 * {@code to_char(…, 'YYYY-MM-DD"T"HH24:MI:SS.USOF')} exactly — six fractional digits always, offset
 * as {@code +00} — and {@code changedFields} is canonicalised here rather than handed to whatever
 * a {@code JsonNode.toString()} happens to produce. {@code HashChainTest} checks both against a
 * real PostgreSQL, so the two implementations cannot diverge unnoticed.
 */
public final class HashChain {

    /** §8.2.4: 32 zero bytes stand in for {@code prev_hash} at the start of a chain. */
    public static final byte[] GENESIS = new byte[32];

    /**
     * Matches {@code to_char(ts, 'YYYY-MM-DD"T"HH24:MI:SS.USOF')} on a UTC connection. Verified
     * against PostgreSQL rather than assumed: {@code ISO_INSTANT} would emit {@code Z} and trim
     * trailing zeros from the fraction, and either difference silently invalidates every hash.
     */
    private static final DateTimeFormatter OCCURRED_AT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, true)
            .appendOffset("+HH", "+00")
            .toFormatter();

    private HashChain() {}

    /**
     * @param prevHash the previous row's hash, or {@link #GENESIS} for {@code chain_seq = 0}
     * @param changedFields already masked (AR-01) — this hashes what is stored, and what is stored
     *     never contains a sensitive value
     */
    public static byte[] rowHash(
            byte[] prevHash,
            UUID eventId,
            Instant occurredAt,
            UUID actorId,
            String entityType,
            UUID entityId,
            String action,
            JsonNode changedFields) {
        MessageDigest digest = sha256();
        digest.update(prevHash == null ? GENESIS : prevHash);
        digest.update(bytes(eventId));
        digest.update(utf8(OCCURRED_AT.format(occurredAt.atOffset(ZoneOffset.UTC))));
        digest.update(utf8(actorId == null ? "" : actorId.toString()));
        digest.update(utf8(entityType));
        digest.update(utf8(entityId.toString()));
        digest.update(utf8(action));
        digest.update(utf8(canonicalJson(changedFields)));
        return digest.digest();
    }

    /**
     * Sorted keys, no whitespace, recursively.
     *
     * <p>Jackson preserves insertion order by default, so two events with the same fields in a
     * different order would hash differently and a later verification would call one of them
     * tampered. Sorting is what makes the hash a function of the content rather than of the order
     * the producer happened to build its map in.
     */
    public static String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeString(names.get(i), out);
                out.append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            // Array order is content, not incidental ordering: reordering one would be a different
            // statement about what happened, so it is not sorted.
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else {
            out.append(node.asText());
        }
    }

    /** Minimal JSON string escaping — enough for what an audit envelope can carry. */
    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** The canonical text the hash is taken over, for tests and for a verification runbook. */
    public static String canonicalTimestamp(Instant occurredAt) {
        return OCCURRED_AT.format(occurredAt.atOffset(ZoneOffset.UTC));
    }

    private static byte[] bytes(UUID uuid) {
        byte[] out = new byte[16];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (hi >>> (56 - 8 * i));
            out[8 + i] = (byte) (lo >>> (56 - 8 * i));
        }
        return out;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
