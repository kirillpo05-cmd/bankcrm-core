package com.client360.common.web;

import java.util.List;

/**
 * Keyset (cursor) pagination envelope (SPEC.md §4.5), mandatory for the interaction timeline and
 * the audit log, where offsets drift under concurrent writes and {@code OFFSET 50000} degrades
 * into a scan.
 */
public record KeysetPage<T>(List<T> content, String nextCursor, boolean hasMore) {}
