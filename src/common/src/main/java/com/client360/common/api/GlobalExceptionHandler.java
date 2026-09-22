package com.client360.common.api;

import com.client360.common.crypto.KeyUnavailableException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The single place that turns exceptions into the SPEC.md §4.3 envelope with a stable code.
 *
 * <p>Nothing here echoes a rejected value or a database message back to the caller: both can
 * carry PII (a typed email, a date of birth in a trigger message). Callers get the field name and
 * the constraint name; the log gets the constraint name and SQLSTATE.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Map<String, ConstraintError> constraintErrors;

    public GlobalExceptionHandler(List<ConstraintError.Registry> registries) {
        Map<String, ConstraintError> merged = new HashMap<>();
        registries.forEach(r -> merged.putAll(r.constraintErrors()));
        this.constraintErrors = Map.copyOf(merged);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException ex) {
        if (ex.status().is5xxServerError()) {
            log.error("request failed: {} {}", ex.code(), ex.getMessage(), ex);
        }
        return ErrorResponses.entity(ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException ex) {
        ApiException api = ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.");
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(e -> api.detail("field", e.getField(), "issue", e.getDefaultMessage()));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(e -> api.detail("field", e.getObjectName(), "issue", e.getDefaultMessage()));
        return ErrorResponses.entity(api);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ErrorResponse> invalidParameters(HandlerMethodValidationException ex) {
        ApiException api = ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.");
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(e -> api.detail("field", name, "issue", e.getDefaultMessage()));
        });
        return ErrorResponses.entity(api);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> invalidArguments(jakarta.validation.ConstraintViolationException ex) {
        ApiException api = ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.");
        ex.getConstraintViolations()
                .forEach(v -> api.detail("field", leaf(v.getPropertyPath().toString()), "issue", v.getMessage()));
        return ErrorResponses.entity(api);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return ErrorResponses.entity(ApiException.validation(path(unknown), "unknown field"));
        }
        if (cause instanceof JsonMappingException mapping && !(cause instanceof JsonParseException)) {
            return ErrorResponses.entity(ApiException.validation(path(mapping), "invalid value"));
        }
        return ErrorResponses.entity(
                ApiException.badRequest(ErrorCodes.MALFORMED_JSON, "Request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException ex) {
        return ErrorResponses.entity(ApiException.validation(ex.getParameterName(), "required"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex) {
        return ErrorResponses.entity(ApiException.validation(ex.getHeaderName(), "required"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ErrorResponses.entity(ApiException.validation(ex.getName(), "invalid value"));
    }

    /** Fallback only: services catch the stale-version case first to populate {@code details[0].current}. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> optimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ErrorResponses.entity(ApiException.conflict(
                ErrorCodes.VERSION_CONFLICT, "The resource was modified concurrently. Reload and retry."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex) {
        return constraintViolation(ex);
    }

    /** CP-EC-13: never serve a partially decrypted record; the whole read fails with 503. */
    @ExceptionHandler(KeyUnavailableException.class)
    ResponseEntity<ErrorResponse> keyUnavailable(KeyUnavailableException ex) {
        log.error("encryption key unavailable", ex);
        return ErrorResponses.entity(
                ApiException.dependencyUnavailable("Encryption service unavailable. Retry shortly."));
    }

    @ExceptionHandler({
        CannotGetJdbcConnectionException.class,
        CannotCreateTransactionException.class,
        DataAccessResourceFailureException.class
    })
    ResponseEntity<ErrorResponse> databaseUnavailable(Exception ex) {
        log.error("database unavailable", ex);
        return ErrorResponses.entity(ApiException.dependencyUnavailable("Database unavailable. Retry shortly."));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> unauthenticated(AuthenticationException ex) {
        return ErrorResponses.entity(HttpStatus.UNAUTHORIZED, ErrorCodes.TOKEN_INVALID, "Authentication required.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException ex) {
        return ErrorResponses.entity(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, "Action not permitted.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unhandled(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse framework) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            return ErrorResponses.entity(status, codeFor(status), messageFor(status));
        }
        PSQLException pg = findCause(ex, PSQLException.class);
        if (pg != null && pg.getSQLState() != null && pg.getSQLState().startsWith("23")) {
            return constraintViolation(ex);
        }
        log.error("unhandled exception", ex);
        return ErrorResponses.entity(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.INTERNAL_ERROR,
                "Unexpected error. Quote the requestId when contacting support.");
    }

    private ResponseEntity<ErrorResponse> constraintViolation(Throwable ex) {
        PSQLException pg = findCause(ex, PSQLException.class);
        String sqlState = pg == null ? "" : String.valueOf(pg.getSQLState());
        String constraint = constraintName(pg);
        ConstraintError mapped = constraint == null ? null : constraintErrors.get(constraint);
        if (mapped != null) {
            log.info("write rejected by constraint {} (sqlstate {})", constraint, sqlState);
            return ErrorResponses.entity(new ApiException(mapped.status(), mapped.code(), mapped.message())
                    .detail("constraint", constraint));
        }
        ApiException api =
                switch (sqlState) {
                    case "23505" -> {
                        log.error("unmapped unique constraint {} — add it to the service's registry", constraint);
                        yield ApiException.conflict(
                                ErrorCodes.RESOURCE_DUPLICATE, "A conflicting record already exists.");
                    }
                    case "23502" ->
                        ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "A required value is missing.");
                    // 23514 check_violation — including the temporal triggers of SPEC §4.12 — and
                    // 23503 foreign_key_violation are both domain-rule failures.
                    default -> ApiException.businessRule("The change violates a data rule.");
                };
        log.info("write rejected by constraint {} (sqlstate {})", constraint, sqlState);
        if (constraint != null) {
            api.detail("constraint", constraint);
        }
        return ErrorResponses.entity(api);
    }

    /**
     * The constraint that rejected the write. Trigger-raised check violations (§4.12) carry no
     * constraint name, so by convention their message starts with the rule's name:
     * {@code ck_clients_adult: …}.
     */
    static String constraintName(PSQLException pg) {
        if (pg == null) {
            return null;
        }
        ServerErrorMessage server = pg.getServerErrorMessage();
        if (server == null) {
            return null;
        }
        if (server.getConstraint() != null) {
            return server.getConstraint();
        }
        String message = server.getMessage();
        if (message != null) {
            int colon = message.indexOf(':');
            if (colon > 0 && message.substring(0, colon).matches("ck_[a-z0-9_]+")) {
                return message.substring(0, colon);
            }
        }
        return null;
    }

    static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String path(JsonMappingException ex) {
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "body" : sb.toString();
    }

    private static String leaf(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> ErrorCodes.VALIDATION_FAILED;
            case NOT_FOUND -> ErrorCodes.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> ErrorCodes.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> ErrorCodes.NOT_ACCEPTABLE;
            case PAYLOAD_TOO_LARGE -> ErrorCodes.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> ErrorCodes.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCodes.INTERNAL_ERROR : status.name();
        };
    }

    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "No such endpoint.";
            case METHOD_NOT_ALLOWED -> "HTTP method not supported on this endpoint.";
            case UNSUPPORTED_MEDIA_TYPE -> "Content type not supported.";
            case PAYLOAD_TOO_LARGE -> "Payload too large.";
            default -> status.getReasonPhrase() + ".";
        };
    }
}
