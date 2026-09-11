package com.client360.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Request facts that belong on an event envelope (SPEC.md §3.3 {@code actor.ip},
 * {@code actor.userAgent}) and in an audit {@code context.endpoint}.
 */
public record RequestMetadata(String requestId, String ip, String userAgent, String method, String path) {

    /** {@code audit_log.user_agent} is {@code VARCHAR(512)}. */
    private static final int USER_AGENT_MAX = 512;

    public static Optional<RequestMetadata> current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        if (userAgent != null && userAgent.length() > USER_AGENT_MAX) {
            userAgent = userAgent.substring(0, USER_AGENT_MAX);
        }
        return Optional.of(new RequestMetadata(
                RequestIds.current(), request.getRemoteAddr(), userAgent, request.getMethod(), request.getRequestURI()));
    }

    /** {@code "PATCH /api/v1/clients/9b21…"} — method and path, never the query string. */
    public String endpoint() {
        return method + " " + path;
    }
}
