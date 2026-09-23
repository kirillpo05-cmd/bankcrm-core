package com.client360.client.api;

import com.client360.client.api.ClientPatch.Field;
import com.client360.client.api.ClientPatch.Kind;
import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ClientStatus;
import com.client360.client.domain.ContactChannel;
import com.client360.client.domain.RiskRating;
import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads a {@code PATCH /clients/{id}} body into a {@link ClientPatch}, rejecting anything
 * malformed with {@code 400 VALIDATION_FAILED} (§5.3).
 *
 * <p>Values are validated against the constraints declared on {@link CreateClientRequest}, not
 * a second copy of them. A create and an edit of the same field cannot disagree about what a
 * valid email or a valid name is — which is the point of validating at both edges (rule 9)
 * rather than at several slightly different ones.
 *
 * <p>Every problem in the body is reported at once, so a form with three bad fields gets three
 * field errors rather than one per round trip. No rejected value is echoed back: the body is PII.
 */
@Component
public class ClientPatchReader {

    private final Validator validator;

    public ClientPatchReader(Validator validator) {
        this.validator = validator;
    }

    public ClientPatch read(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest(ErrorCodes.MALFORMED_JSON, "A PATCH body must be a JSON object.");
        }
        Map<Field, Object> values = new EnumMap<>(Field.class);
        List<String[]> problems = new ArrayList<>();

        for (Map.Entry<String, JsonNode> entry : body.properties()) {
            String name = entry.getKey();
            JsonNode node = entry.getValue();

            Field field = Field.byJsonName(name).orElse(null);
            if (field == null) {
                problems.add(new String[] {name, "unknown or read-only field"});
                continue;
            }
            // Presence is all the service needs to refuse these; parsing a value it will never
            // write would only produce a misleading 400 ahead of the real 403 or 422.
            if (field.kind() == Kind.IMMUTABLE || field.kind() == Kind.ELSEWHERE) {
                values.put(field, null);
                continue;
            }
            if (node.isNull()) {
                if (field.nullable()) {
                    values.put(field, null);
                } else {
                    problems.add(new String[] {name, "may not be null"});
                }
                continue;
            }
            String issue = parse(field, node, values);
            if (issue != null) {
                problems.add(new String[] {name, issue});
            }
        }

        if (!problems.isEmpty()) {
            ApiException error = ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.");
            problems.forEach(p -> error.detail("field", p[0], "issue", p[1]));
            throw error;
        }
        return new ClientPatch(values);
    }

    /** @return the problem with the value, or {@code null} once it has been stored */
    private String parse(Field field, JsonNode node, Map<Field, Object> values) {
        return switch (field) {
            case PREFERRED_CHANNEL -> parseEnum(field, node, ContactChannel.class, values);
            case SEGMENT -> parseEnum(field, node, ClientSegment.class, values);
            case RISK -> parseEnum(field, node, RiskRating.class, values);
            case STATUS -> parseEnum(field, node, ClientStatus.class, values);
            case DATE_OF_BIRTH -> {
                if (!node.isTextual()) {
                    yield "must be a date in yyyy-MM-dd form";
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(node.textValue());
                } catch (DateTimeParseException e) {
                    yield "must be a date in yyyy-MM-dd form";
                }
                yield validateAndStore(field, date, values);
            }
            default -> node.isTextual() ? validateAndStore(field, node.textValue(), values) : "must be a string";
        };
    }

    private <E extends Enum<E>> String parseEnum(Field field, JsonNode node, Class<E> type, Map<Field, Object> values) {
        if (node.isTextual()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(node.textValue())) {
                    values.put(field, constant);
                    return null;
                }
            }
        }
        return "must be one of " + Arrays.toString(type.getEnumConstants());
    }

    private String validateAndStore(Field field, Object value, Map<Field, Object> values) {
        var violations = validator.validateValue(CreateClientRequest.class, field.json(), value);
        if (!violations.isEmpty()) {
            return violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .findFirst()
                    .orElse("invalid value");
        }
        values.put(field, value);
        return null;
    }
}
