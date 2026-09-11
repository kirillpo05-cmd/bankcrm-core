package com.client360.common.api;

import com.client360.common.web.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Builds the §4.3 envelope. Used by the MVC exception handler and by the security filter chain,
 * which rejects requests before they reach a controller — both must emit the same shape.
 */
@Component
public class ErrorResponses {

    private final ObjectMapper objectMapper;

    public ErrorResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ErrorResponse body(
            HttpStatus status, String code, String message, List<Map<String, Object>> details) {
        return new ErrorResponse(
                // Response metadata, not a stored business timestamp: rule 7 does not apply.
                Instant.now(), status.value(), status.name(), code, message, RequestIds.current(), List.copyOf(details));
    }

    public static ResponseEntity<ErrorResponse> entity(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .headers(ex.headers())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(ex.status(), ex.code(), ex.getMessage(), ex.details()));
    }

    public static ResponseEntity<ErrorResponse> entity(HttpStatus status, String code, String message) {
        return entity(new ApiException(status, code, message));
    }

    /** For filters that run before Spring MVC and therefore cannot throw into the advice. */
    public void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        objectMapper.writeValue(response.getOutputStream(), body(status, code, message, List.of()));
    }
}
