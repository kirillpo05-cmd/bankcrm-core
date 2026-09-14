package com.client360.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Client Profile (SPEC.md §5) and, from v2, RBAC (§9).
 *
 * <p>Scans {@code com.client360.common} as well as its own package: the error envelope, the
 * outbox, idempotency, field encryption and the {@code AccessPolicy} seam are shared machinery,
 * not copies per service.
 */
@SpringBootApplication(scanBasePackages = {"com.client360.client", "com.client360.common"})
public class ClientServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientServiceApplication.class, args);
    }
}
