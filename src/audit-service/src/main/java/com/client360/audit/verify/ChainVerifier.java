package com.client360.audit.verify;

import com.client360.audit.chain.HashChain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-walks a hash chain and says whether it still adds up (SPEC.md §8.3, AT-BR-05, AT-US-03).
 *
 * <p>Three different things can be wrong, and they are reported apart because they mean different
 * things to whoever is holding the report:
 *
 * <ul>
 *   <li><strong>{@code ROW_MODIFIED_OR_DELETED}</strong> — a row's stored hash is not what its own
 *       contents produce. Somebody edited the row and did not recompute.
 *   <li><strong>{@code CHAIN_BROKEN}</strong> — a row's {@code prev_hash} is not the previous row's
 *       hash, or a sequence number is missing. Somebody removed or reordered a row. This is the
 *       case a per-row checksum would never catch, and the reason the hash covers its predecessor.
 *   <li><strong>{@code SEALED_HEAD_MISMATCH}</strong> — every link is internally consistent but the
 *       head does not match what was sealed into {@code audit_chain_head}. Somebody rewrote the
 *       chain and recomputed it end to end. Local recomputation alone must not defeat detection,
 *       which is the whole point of sealing heads and mirroring them off-box (AT-BR-06).
 * </ul>
 *
 * <p>A detected break is a {@code 200} with {@code verified: false}, never a {@code 500} (S-AT-03).
 * The request succeeded; the data did not. Conflating the two would mean an auditor could not tell
 * "the log is intact" from "the check could not run", which are opposite answers.
 */
@Service
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ChainVerifier(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public VerificationReport verify(Integer chainId, Long fromSeq, Long toSeq, Instant from, Instant to) {
        long started = System.nanoTime();
        List<Integer> chains = chainId != null ? List.of(chainId) : chainsIn(from, to);

        List<ChainResult> results = new ArrayList<>();
        List<Break> breaks = new ArrayList<>();
        long rowsChecked = 0;
        boolean externalHeadMatch = true;

        for (int chain : chains) {
            ChainResult result = verifyOne(chain, fromSeq, toSeq, from, to, breaks);
            results.add(result);
            rowsChecked += result.rowsChecked();
            externalHeadMatch &= result.sealedHeadMatched();
        }

        boolean verified = breaks.isEmpty();
        if (!verified) {
            // AT-US-03: a break alerts regardless of who asked. Someone checking their own work
            // must not be the only person who learns the answer.
            log.error(
                    "CRITICAL audit chain verification failed: {} break(s) across {} chain(s)",
                    breaks.size(),
                    chains.size());
        }
        return new VerificationReport(
                verified,
                results,
                List.copyOf(breaks),
                rowsChecked,
                (System.nanoTime() - started) / 1_000_000,
                externalHeadMatch);
    }

    private ChainResult verifyOne(int chainId, Long fromSeq, Long toSeq, Instant from, Instant to, List<Break> breaks) {
        List<Row> rows = rows(chainId, fromSeq, toSeq, from, to);
        if (rows.isEmpty()) {
            return new ChainResult(chainId, null, null, 0, null, true);
        }

        byte[] previousHash = null;
        Long previousSeq = null;
        for (Row row : rows) {
            byte[] expected = HashChain.rowHash(
                    row.prevHash(),
                    row.eventId(),
                    row.occurredAt(),
                    row.actorId(),
                    row.entityType(),
                    row.entityId(),
                    row.action(),
                    row.changedFields());
            if (!Arrays.equals(expected, row.rowHash())) {
                breaks.add(new Break(
                        chainId,
                        row.chainSeq(),
                        row.occurredAt(),
                        hex(expected),
                        hex(row.rowHash()),
                        "ROW_MODIFIED_OR_DELETED"));
            }
            // The link itself, which is what catches a removal rather than an edit. Only checked
            // from the second row of the walk: the first may legitimately be mid-chain when the
            // caller asked for a range.
            if (previousHash != null) {
                if (!Arrays.equals(previousHash, row.prevHash())) {
                    breaks.add(new Break(
                            chainId,
                            row.chainSeq(),
                            row.occurredAt(),
                            hex(previousHash),
                            hex(row.prevHash()),
                            "CHAIN_BROKEN"));
                } else if (row.chainSeq() != previousSeq + 1) {
                    breaks.add(new Break(chainId, row.chainSeq(), row.occurredAt(), null, null, "CHAIN_BROKEN"));
                }
            }
            previousHash = row.rowHash();
            previousSeq = row.chainSeq();
        }

        Row last = rows.getLast();
        boolean sealedMatched = sealedHeadMatches(chainId, last, breaks);
        return new ChainResult(
                chainId, rows.getFirst().chainSeq(), last.chainSeq(), rows.size(), hex(last.rowHash()), sealedMatched);
    }

    /**
     * AT-BR-06. A chain rewritten and recomputed end to end verifies perfectly against itself; only
     * a head held elsewhere catches it. The sealed heads here are the local half of that — the
     * nightly mirror to WORM storage is the half an attacker with database access cannot reach.
     */
    private boolean sealedHeadMatches(int chainId, Row last, List<Break> breaks) {
        Optional<byte[]> sealed = jdbc.sql("SELECT head_hash FROM audit_chain_head"
                        + " WHERE chain_id = :chainId AND chain_seq <= :seq ORDER BY chain_seq DESC LIMIT 1")
                .param("chainId", chainId)
                .param("seq", last.chainSeq())
                .query(byte[].class)
                .optional();
        if (sealed.isEmpty()) {
            // Nothing sealed yet for this chain. Not a break: chains are sealed every 1 000 rows,
            // so a young chain has no head to compare against and saying otherwise would cry wolf.
            return true;
        }
        long sealedSeq = jdbc.sql("SELECT chain_seq FROM audit_chain_head"
                        + " WHERE chain_id = :chainId AND chain_seq <= :seq ORDER BY chain_seq DESC LIMIT 1")
                .param("chainId", chainId)
                .param("seq", last.chainSeq())
                .query(Long.class)
                .single();
        byte[] atSeal = jdbc.sql("SELECT row_hash FROM audit_log WHERE chain_id = :chainId AND chain_seq = :seq")
                .param("chainId", chainId)
                .param("seq", sealedSeq)
                .query(byte[].class)
                .optional()
                .orElse(null);

        if (atSeal == null || !Arrays.equals(sealed.get(), atSeal)) {
            breaks.add(new Break(chainId, sealedSeq, null, hex(sealed.get()), hex(atSeal), "SEALED_HEAD_MISMATCH"));
            return false;
        }
        return true;
    }

    private List<Integer> chainsIn(Instant from, Instant to) {
        return jdbc.sql("SELECT DISTINCT chain_id FROM audit_log"
                        + " WHERE (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz))"
                        + "   AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz))"
                        + " ORDER BY chain_id")
                .param("from", from == null ? null : from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to == null ? null : to.atOffset(java.time.ZoneOffset.UTC))
                .query(Integer.class)
                .list();
    }

    private List<Row> rows(int chainId, Long fromSeq, Long toSeq, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT chain_seq, event_id, occurred_at, actor_id, entity_type, entity_id,
                               action::text AS action, changed_fields, prev_hash, row_hash
                          FROM audit_log
                         WHERE chain_id = :chainId
                           AND (CAST(:fromSeq AS bigint) IS NULL OR chain_seq >= CAST(:fromSeq AS bigint))
                           AND (CAST(:toSeq AS bigint) IS NULL OR chain_seq <= CAST(:toSeq AS bigint))
                           AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz))
                         ORDER BY chain_seq
                        """)
                .param("chainId", chainId)
                .param("fromSeq", fromSeq)
                .param("toSeq", toSeq)
                .param("from", from == null ? null : from.atOffset(java.time.ZoneOffset.UTC))
                .param("to", to == null ? null : to.atOffset(java.time.ZoneOffset.UTC))
                .query(this::map)
                .list();
    }

    private Row map(ResultSet rs, int rowNum) throws SQLException {
        String changed = rs.getString("changed_fields");
        JsonNode fields = null;
        if (changed != null) {
            try {
                fields = objectMapper.readTree(changed);
            } catch (Exception e) {
                // Unreadable JSON in a stored row is itself evidence of tampering; leaving it null
                // makes the hash mismatch, which is exactly the report the caller should get.
                fields = null;
            }
        }
        OffsetDateTime occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
        return new Row(
                rs.getLong("chain_seq"),
                rs.getObject("event_id", UUID.class),
                occurredAt.toInstant(),
                rs.getObject("actor_id", UUID.class),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("action"),
                fields,
                rs.getBytes("prev_hash"),
                rs.getBytes("row_hash"));
    }

    private static String hex(byte[] value) {
        return value == null ? null : HexFormat.of().formatHex(value);
    }

    private record Row(
            long chainSeq,
            UUID eventId,
            Instant occurredAt,
            UUID actorId,
            String entityType,
            UUID entityId,
            String action,
            JsonNode changedFields,
            byte[] prevHash,
            byte[] rowHash) {}

    public record VerificationReport(
            boolean verified,
            List<ChainResult> chains,
            List<Break> breaks,
            long rowsChecked,
            long durationMs,
            boolean externalHeadMatch) {}

    public record ChainResult(
            int chainId, Long fromSeq, Long toSeq, int rowsChecked, String headHash, boolean sealedHeadMatched) {}

    /** @param diagnosis which of the three things went wrong; see the class javadoc */
    public record Break(
            int chainId, long chainSeq, Instant occurredAt, String expectedHash, String actualHash, String diagnosis) {}
}
