package com.client360.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The error envelope, exactly as SPEC.md §4.3 shapes it. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String requestId,
        List<Map<String, Object>> details) {}
