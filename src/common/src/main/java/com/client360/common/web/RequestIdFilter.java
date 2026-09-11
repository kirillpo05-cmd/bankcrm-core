package com.client360.common.web;

import com.client360.common.id.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns the request id before anything else runs — including Spring Security, so a {@code 401}
 * carries a {@code requestId} too — and seeds the log MDC.
 *
 * <p>SPEC.md §4.2: echoed back, generated when absent. A supplied id must be a UUID because it is
 * stored in {@code audit_log.request_id UUID}; anything else is replaced rather than trusted.
 *
 * <p>The MDC carries {@code traceId}, {@code userId} and {@code endpoint} and nothing else
 * (CLAUDE.md rule 10). {@code endpoint} is method plus path — never the query string, which can
 * hold an email or phone number on {@code /clients/lookup}.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(RequestIds.HEADER));
        request.setAttribute(RequestIds.ATTRIBUTE, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        MDC.put("traceId", requestId);
        MDC.put("endpoint", request.getMethod() + " " + request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("endpoint");
            MDC.remove("userId");
        }
    }

    static String resolve(String supplied) {
        if (supplied != null && supplied.length() == 36) {
            try {
                return UUID.fromString(supplied).toString();
            } catch (IllegalArgumentException ignored) {
                // fall through: not a UUID, generate our own
            }
        }
        return UuidV7.next().toString();
    }
}
