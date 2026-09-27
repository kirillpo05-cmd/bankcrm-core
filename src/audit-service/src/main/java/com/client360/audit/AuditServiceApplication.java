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
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
