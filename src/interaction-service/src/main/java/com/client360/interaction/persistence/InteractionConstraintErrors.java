package com.client360.interaction.persistence;

import com.client360.common.api.ConstraintError;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Named constraints of schema {@code interaction} → API errors.
 *
 * <p>The service pre-checks most of these so the caller gets a message naming the field. This
 * registry is the backstop for what a pre-check cannot close — a race, or a path that forgot — so
 * the database stays the last line rather than the only one (rule 9).
 *
 * <p>No message echoes a rejected value: subjects and bodies are written about customers.
 */
@Component
public class InteractionConstraintErrors implements ConstraintError.Registry {

    @Override
    public Map<String, ConstraintError> constraintErrors() {
        return Map.ofEntries(
                // Trigger-raised (§4.12), recovered from the "ck_...:" message prefix.
                Map.entry(
                        "ck_interactions_not_future",
                        ConstraintError.businessRule(
                                "occurredAt may be at most 5 minutes ahead of server time (IL-EC-02).")),
                Map.entry(
                        "ck_interactions_duration",
                        ConstraintError.businessRule("Only a CALL or a MEETING has a duration.")),
                Map.entry(
                        "ck_interactions_direction",
                        ConstraintError.businessRule("A NOTE or a TICKET is always INTERNAL.")),
                Map.entry(
                        "ck_interactions_ticket_fields",
                        ConstraintError.businessRule("Ticket fields are present exactly when the type is TICKET.")),
                Map.entry(
                        "ck_interactions_no_self_correct",
                        ConstraintError.businessRule("An interaction cannot correct itself (IL-BR-03).")),
                // IL-BR-04: a row that reaches the cap is automation misbehaving, not a person
                // fixing typos — the constraint stops it rather than letting it run on.
                Map.entry(
                        "ck_interactions_edit_count",
                        ConstraintError.businessRule(
                                "This interaction has reached its edit limit and is flagged for review (IL-BR-04).")),
                Map.entry(
                        "ck_interactions_delete_reason",
                        ConstraintError.businessRule("A deletion reason is required.")),
                // IL-BR-12: the import path returns the existing row with 200; reaching this means
                // two imports of the same external record raced.
                Map.entry(
                        "ux_int_external_ref",
                        ConstraintError.conflict(
                                "INTERACTION_DUPLICATE_EXTERNAL_REF",
                                "This external record has already been imported.")),
                Map.entry(
                        "fk_int_corrects",
                        ConstraintError.businessRule("The interaction being corrected does not exist.")));
    }
}
