package com.client360.interaction.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /interactions/{id}/corrections} body (SPEC.md §6.3, IL-BR-03).
 *
 * <p>Only the wording is supplied. Everything else about a correction is decided by the original it
 * amends — see {@code InteractionService#correct} for what is inherited and why.
 */
public record CreateCorrectionRequest(
        @NotBlank @Size(max = 200) String subject,
        @Size(max = 10_000) String body) {}
