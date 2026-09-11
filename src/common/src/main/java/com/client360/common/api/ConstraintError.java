package com.client360.common.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * How a named database constraint surfaces through the API. Each service contributes a
 * {@link Registry} bean for the constraints in its own schema.
 *
 * <p>The service layer should pre-check the common cases and produce a richer error (for example
 * {@code details[0].conflictingId}); this mapping is the fallback for the race the pre-check cannot
 * close. The database stays the last line of defence (CLAUDE.md rule 9).
 */
public record ConstraintError(HttpStatus status, String code, String message) {

    public static ConstraintError conflict(String code, String message) {
        return new ConstraintError(HttpStatus.CONFLICT, code, message);
    }

    public static ConstraintError businessRule(String message) {
        return new ConstraintError(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.BUSINESS_RULE_VIOLATED, message);
    }

    /** Constraint name → API error, contributed per service. */
    public interface Registry {
        Map<String, ConstraintError> constraintErrors();
    }
}
