package com.client360.interaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Interaction Log (SPEC.md §6) and, from v3, Task/Reminder (§7).
 *
 * <p>Holds no client or user table: it stores {@code client_id} and {@code author_id} as plain
 * UUIDs and asks client-service — the authorization authority — whether the caller may act on a
 * client (§3.1).
 */
@SpringBootApplication(scanBasePackages = {"com.client360.interaction", "com.client360.common"})
public class InteractionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteractionServiceApplication.class, args);
    }
}
