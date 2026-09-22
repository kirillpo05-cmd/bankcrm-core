package com.client360.interaction.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A staff member as an author or assignee. Resolved through client-service, which owns the user
 * table (§3.1); this service only ever holds the id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSummary(UUID id, String fullName) {}
