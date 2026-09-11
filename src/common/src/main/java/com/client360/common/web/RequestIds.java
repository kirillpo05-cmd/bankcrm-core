package com.client360.common.web;

import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The request id of the current request. SPEC.md §10.5: {@code traceId == X-Request-Id ==
 * audit request_id}, so one identifier on a support ticket resolves to a log line, a trace and an
 * audit row.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    static final String ATTRIBUTE = RequestIds.class.getName() + ".id";

    private RequestIds() {}

    /** The current request id, or {@code null} outside a request (scheduled jobs, consumers). */
    public static String current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object id = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return id instanceof String s ? s : null;
    }

    public static UUID currentUuid() {
        String id = current();
        return id == null ? null : UUID.fromString(id);
    }
}
