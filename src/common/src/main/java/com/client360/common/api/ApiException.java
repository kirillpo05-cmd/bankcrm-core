package com.client360.common.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * A failure that maps one-to-one onto the error envelope (SPEC.md §4.3).
 *
 * <p>The message reaches the API response, so it must never contain PII (CLAUDE.md rule 10).
 * Put identifiers in {@link #detail}, never values the user typed.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<Map<String, Object>> details = new ArrayList<>();
    private final HttpHeaders headers = new HttpHeaders();

    public ApiException(HttpStatus status, String code, String message) {
        // 4xx outcomes are expected control flow; a stack trace per rejected request is noise.
        super(message, null, false, status.is5xxServerError());
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException validation(String field, String issue) {
        return badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.")
                .detail("field", field, "issue", issue);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException businessRule(String message) {
        return unprocessable(ErrorCodes.BUSINESS_RULE_VIOLATED, message);
    }

    public static ApiException preconditionRequired(String message) {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, ErrorCodes.PRECONDITION_REQUIRED, message);
    }

    public static ApiException dependencyUnavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE, message)
                .header(HttpHeaders.RETRY_AFTER, "5");
    }

    /** Appends one entry to {@code details}; arguments are alternating key/value pairs. */
    public ApiException detail(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("detail() takes key/value pairs");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entry.put((String) keyValues[i], keyValues[i + 1]);
        }
        details.add(entry);
        return this;
    }

    public ApiException detail(Map<String, Object> entry) {
        details.add(new LinkedHashMap<>(entry));
        return this;
    }

    public ApiException header(String name, String value) {
        headers.set(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<Map<String, Object>> details() {
        return details;
    }

    public HttpHeaders headers() {
        return headers;
    }
}
