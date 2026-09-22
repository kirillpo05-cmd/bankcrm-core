package com.client360.client.api;

import com.client360.client.domain.KycStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code POST /clients/{id}/kyc} body (SPEC.md §5.3).
 *
 * <p>Only shape is validated here. Which fields a target requires, and the 6–60 month validity
 * window, are business rules that §5.3 answers with {@code 422}, not {@code 400} — so they live
 * in the service, where the target status is known.
 *
 * @param verifiedOn the date on the verified documents, not the date of the request
 * @param note evidence recorded with the decision; stored encrypted
 * @param reason required when rejecting
 */
public record KycTransitionRequest(
        @NotNull KycStatus targetStatus,
        @JsonFormat(pattern = "uuuu-MM-dd") LocalDate verifiedOn,
        Integer validityMonths,
        @Size(max = 2000) String note,
        @Size(max = 2000) String reason) {}
