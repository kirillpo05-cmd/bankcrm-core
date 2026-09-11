package com.client360.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7: 48-bit Unix-millisecond timestamp, then random bits (CLAUDE.md →
 * Conventions). Time-sortable, which makes the id a safe keyset tiebreaker (SPEC.md §4.1).
 *
 * <p>The embedded time comes from the JVM clock. That is fine: it orders identifiers and is never
 * read back as a business timestamp. Business time is PostgreSQL {@code now()} (CLAUDE.md rule 7).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID next() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong();
        long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | randA;
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
