package com.client360.client.api;

import com.client360.client.domain.ClientSegment;
import com.client360.client.domain.ContactChannel;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code POST /clients} body (SPEC.md §5.3).
 *
 * <p>Validated at both edges (CLAUDE.md rule 9): the annotations here produce a field-level
 * {@code 400} with a usable message, and the same invariants are {@code CHECK} constraints in the
 * schema. The database is the last line, not the only one — the minimum-age rule, for instance,
 * is a trigger because {@code CURRENT_DATE} cannot appear in a {@code CHECK} (§4.12), and it is
 * re-stated here only so the caller gets a better message than a constraint name.
 *
 * <p>{@code status}, {@code risk} and {@code kycStatus} are absent on purpose. A client is always
 * created {@code ACTIVE} / {@code LOW} / {@code NOT_STARTED}; moving KYC on is a separate,
 * separately-permissioned call (CP-BR-05).
 */
public record CreateClientRequest(
        @NotBlank @Size(max = 32) String externalRef,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Size(max = 100) String middleName,
        @NotNull @Past @JsonFormat(pattern = "uuuu-MM-dd") LocalDate dateOfBirth,
        @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 32) String phone,
        @Size(max = 64) String taxId,
        @Size(max = 500) String address,
        ContactChannel preferredChannel,
        ClientSegment segment,
        @NotNull UUID ownerManagerId) {

    /** Column defaults, applied here so the response echoes what was stored. */
    public CreateClientRequest {
        preferredChannel = preferredChannel == null ? ContactChannel.PHONE : preferredChannel;
        segment = segment == null ? ClientSegment.RETAIL : segment;
    }
}
