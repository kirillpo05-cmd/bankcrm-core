package com.client360.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Audit Trail (SPEC.md §8).
 *
 * <p>This service is a <strong>consumer only</strong>. There is no ingestion endpoint and none may
 * be added: audit entries are created solely by consuming Kafka, so a compromised application
 * service cannot forge one without also compromising the broker (AT-BR-02).
 *
 * <p>Nothing updates or deletes an audit row — not an endpoint, not a role, not a repair script.
 * {@code audit:delete} does not exist as a permission code (AT-BR-01).
 */
/**
 * Scans only the parts of {@code common} this service may legitimately hold.
 *
 * <p>Not {@code com.client360.common} wholesale, and the omission is the point: that would wire in
 * {@code CryptoConfig} and hand this service an AES data key. It has no use for one — AT-BR-04 says
 * sensitive values are never stored here, even encrypted, so there is nothing to decrypt and no
 * reason for the key to be reachable from a machine whose whole job is to keep evidence. The outbox
 * and idempotency machinery is left out for the same kind of reason: this service publishes nothing
 * and accepts no writes.
 */
@SpringBootApplication(
        scanBasePackages = {
            "com.client360.audit",
            "com.client360.common.security",
            "com.client360.common.api",
            "com.client360.common.web"
        })
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
