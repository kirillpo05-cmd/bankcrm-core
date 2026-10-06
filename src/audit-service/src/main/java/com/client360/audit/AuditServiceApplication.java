package com.client360.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Audit Trail (SPEC.md §8).
 *
 * <p>This service is a <strong>consumer only</strong>. There is no ingestion endpoint and none may
 * be added: audit entries are created solely by consuming Kafka, so a compromised application
 * service cannot forge one without also compromising the broker (AT-BR-02).
 *
 * <p>Nothing updates or deletes an audit row — not an endpoint, not a role, not a repair script.
 * {@code audit:delete} does not exist as a permission code (AT-BR-01).
 *
 * <h2>What of {@code common} it scans</h2>
 *
 * <p>Only the parts it may legitimately hold. Not {@code com.client360.common} wholesale, and the omission is the point: that would wire in
 * {@code CryptoConfig} and hand this service an AES data key. It has no use for one — AT-BR-04 says
 * sensitive values are never stored here, even encrypted, so there is nothing to decrypt and no
 * reason for the key to be reachable from a machine whose whole job is to keep evidence.
 * Idempotency is left out too: this service accepts no writes, so there is no request to replay.
 *
 * <p>The <strong>outbox</strong> is here, and it looked like a contradiction until AT-BR-10 was
 * built. Reading the audit log is itself audited, and the obvious implementation of that is the one
 * thing AT-BR-02 forbids: the read handler inserting its own row. So a read writes an outbox row,
 * the relay publishes it to {@code audit.events}, and the same consumer that stores everyone else's
 * events stores this one. This service still has no more privilege over {@code audit_log} than any
 * other producer — including over its own events, which is the property that makes the round trip
 * worth the cost.
 */
// CommonConfig lives in com.client360.common itself and carries @EnableScheduling, and this service
// deliberately does not scan that package — so scheduling is enabled here explicitly rather than by
// widening the scan to reach one annotation and pulling CryptoConfig in with it. Without it the
// outbox relay would never poll and the partition jobs would never run.
@EnableScheduling
// An export runs off the request thread (AT-EC-09): a regulatory slice can be millions of rows, and
// a request that waits for it is a request that times out. Boot's own bounded task executor runs
// it, so the work queues rather than spawning a thread per export.
@EnableAsync
@SpringBootApplication(
        scanBasePackages = {
            "com.client360.audit",
            "com.client360.common.security",
            "com.client360.common.api",
            "com.client360.common.web",
            // Self-audit only (AT-BR-10). Nothing here writes to audit_log.
            "com.client360.common.outbox",
            "com.client360.common.time",
            // §8.3's five exports an hour. Counted in the database, so it holds across replicas.
            "com.client360.common.ratelimit"
        })
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
