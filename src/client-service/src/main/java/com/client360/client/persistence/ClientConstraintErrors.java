package com.client360.client.persistence;

import com.client360.client.api.ClientErrorCodes;
import com.client360.common.api.ConstraintError;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Named constraints of schema {@code client} → API errors.
 *
 * <p>The service layer pre-checks the common duplicates so it can answer with
 * {@code details[0].conflictingId}; this registry is the fallback for the race that pre-check
 * cannot close — two creates for the same email arriving at once. Either way the caller sees the
 * same code, and the database stays the last line of defence (CLAUDE.md rule 9).
 *
 * <p>Messages here never echo the rejected value. A duplicate-email message that quoted the
 * address would put PII in an error body and, from there, in whatever logs it.
 */
@Component
public class ClientConstraintErrors implements ConstraintError.Registry {

    @Override
    public Map<String, ConstraintError> constraintErrors() {
        return Map.ofEntries(
                // Identity uniqueness (CP-BR-01, CP-BR-02). All three indexes are partial on
                // deleted_at IS NULL, so an identifier frees up after erasure (CP-EC-02).
                Map.entry(
                        "ux_clients_external_ref",
                        ConstraintError.conflict(
                                ClientErrorCodes.CLIENT_DUPLICATE_EXTERNAL_REF,
                                "A client with this external reference already exists.")),
                Map.entry(
                        "ux_clients_email_hash",
                        ConstraintError.conflict(
                                ClientErrorCodes.CLIENT_DUPLICATE_EMAIL,
                                "A client with this email address already exists.")),
                Map.entry(
                        "ux_clients_tax_hash",
                        ConstraintError.conflict(
                                ClientErrorCodes.CLIENT_DUPLICATE_TAX_ID, "A client with this tax ID already exists.")),

                // Trigger-raised, so it arrives with no constraint name of its own; the handler
                // recovers the rule from the "ck_...:" message prefix (SPEC.md §4.12).
                Map.entry(
                        "ck_clients_adult",
                        ConstraintError.businessRule("Client must be at least 18 years old (CP-BR-14).")),
                Map.entry(
                        "ck_clients_dob_sane",
                        ConstraintError.businessRule("Date of birth is outside the supported range.")),

                // KYC field coherence (CP-BR-04). Reaching these means the service let an
                // inconsistent KYC block through; they are a backstop, not an expected path.
                Map.entry(
                        "ck_clients_kyc_verified_fields",
                        ConstraintError.businessRule(
                                "A verified KYC record needs both a verification and an expiry date.")),
                Map.entry(
                        "ck_clients_kyc_rejected_reason",
                        ConstraintError.businessRule("A rejected KYC record needs a reason.")),
                Map.entry(
                        "ck_clients_kyc_expiry_after_verify",
                        ConstraintError.businessRule("KYC expiry must be later than the verification date.")),

                // Merge invariants (CP-BR-10).
                Map.entry(
                        "ck_clients_no_self_merge",
                        ConstraintError.businessRule("A client cannot be merged into itself.")),
                Map.entry(
                        "ck_clients_merge_pair",
                        ConstraintError.businessRule("A merge must record both the survivor and the time.")),

                // Soft delete (§4.9) always carries a reason.
                Map.entry("ck_clients_delete_reason", ConstraintError.businessRule("A deletion reason is required.")),

                // RESTRICT on the owner is what makes orphaned clients impossible (CP-EC-06).
                Map.entry("fk_clients_owner", ConstraintError.businessRule("The owning manager does not exist.")),
                Map.entry("fk_clients_team", ConstraintError.businessRule("The team does not exist.")),

                // Products are a projection of core banking (§5.2.3).
                Map.entry(
                        "uq_products_external",
                        ConstraintError.conflict(
                                ClientErrorCodes.PRODUCT_DUPLICATE_EXTERNAL_ID,
                                "This product is already recorded for the client.")),
                Map.entry(
                        "ck_products_opened_not_future",
                        ConstraintError.businessRule("A product cannot be opened in the future.")),
                Map.entry(
                        "ck_products_currency",
                        ConstraintError.businessRule("Currency must be a three-letter ISO code.")));
    }
}
