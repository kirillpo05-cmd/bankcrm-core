package com.client360.interaction.api;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated {@code PATCH /interactions/{id}} body (SPEC.md §6.3, IL-US-04).
 *
 * <p>Read from a raw {@link JsonNode} rather than bound to a DTO for one reason: the fields that may
 * never change have to be <em>recognised</em> to be refused properly. §6.3 answers an attempt to
 * change {@code type}, {@code clientId} or {@code occurredAt} with {@code 422} — "you may not" — and
 * a DTO without those fields would answer {@code 400 unknown field} instead, which tells the caller
 * the wrong thing.
 *
 * @param subject the new subject, or {@code null} to leave it
 * @param body the new body, or {@code null} to leave it
 * @param immutableFields which substance fields the caller tried to change (IL-BR-01). Refused by
 *     the service after authorization, so a caller without access still gets the 404 first.
 */
public record InteractionEdit(String subject, String body, List<String> immutableFields) {

    private static final int SUBJECT_MAX = 200;
    private static final int BODY_MAX = 10_000;

    /**
     * IL-BR-01: substance never changes. Everything but the wording is here — not just the three
     * §6.3 names — because changing visibility, outcome or duration after the fact rewrites what
     * happened just as surely.
     */
    private static final Set<String> IMMUTABLE = Set.of(
            "type",
            "clientId",
            "occurredAt",
            "authorId",
            "direction",
            "durationSeconds",
            "outcome",
            "visibility",
            "source",
            "correctsId");

    public static InteractionEdit read(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw ApiException.badRequest(ErrorCodes.MALFORMED_JSON, "A PATCH body must be a JSON object.");
        }
        String subject = null;
        String body = null;
        List<String> immutable = new ArrayList<>();
        List<Map.Entry<String, String>> problems = new ArrayList<>();

        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String name = field.getKey();
            JsonNode value = field.getValue();
            switch (name) {
                case "subject" -> {
                    if (!value.isTextual() || value.textValue().isBlank()) {
                        problems.add(Map.entry(name, "must be a non-empty string"));
                    } else if (value.textValue().length() > SUBJECT_MAX) {
                        problems.add(Map.entry(name, "must be at most " + SUBJECT_MAX + " characters"));
                    } else {
                        subject = value.textValue().trim();
                    }
                }
                case "body" -> {
                    if (!value.isTextual()) {
                        problems.add(Map.entry(name, "must be a string"));
                    } else if (value.textValue().length() > BODY_MAX) {
                        problems.add(Map.entry(name, "must be at most " + BODY_MAX + " characters"));
                    } else {
                        body = value.textValue();
                    }
                }
                default -> {
                    if (IMMUTABLE.contains(name)) {
                        immutable.add(name);
                    } else {
                        problems.add(Map.entry(name, "unknown or read-only field"));
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            ApiException error = ApiException.badRequest(ErrorCodes.VALIDATION_FAILED, "Request validation failed.");
            problems.forEach(p -> error.detail("field", p.getKey(), "issue", p.getValue()));
            throw error;
        }
        return new InteractionEdit(subject, body, List.copyOf(immutable));
    }
}
