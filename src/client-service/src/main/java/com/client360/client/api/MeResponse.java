package com.client360.client.api;

import com.client360.client.persistence.TeamRepository;
import com.client360.client.service.AuthService;
import com.client360.common.security.Scope;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /me} (SPEC.md §9.3).
 *
 * <p>{@code permissions} is the contract that keeps role strings out of the frontend: the SPA builds
 * its entire navigation from these codes and scopes, so adding a role never requires a frontend
 * release, and a screen can never be shown on the strength of a role name that the API would refuse
 * on the strength of a permission (RB-BR-01).
 */
public record MeResponse(
        UUID id,
        String fullName,
        String email,
        String status,
        List<Role> roles,
        Team primaryTeam,
        List<Membership> teams,
        List<Permission> permissions,
        List<Grant> activeGrants,
        Instant sessionExpiresAt) {

    public static MeResponse of(AuthService.Me me) {
        return new MeResponse(
                me.id(),
                me.fullName(),
                me.email(),
                me.status(),
                me.roles().stream()
                        .map(role -> new Role(role.code(), role.expiresAt()))
                        .toList(),
                Team.of(me.primaryTeam()),
                me.teams().stream()
                        .map(team -> new Membership(team.teamId(), team.isPrimary()))
                        .toList(),
                permissions(me.permissions()),
                me.activeGrants().stream()
                        .map(grant -> new Grant(grant.id(), grant.clientId(), grant.expiresAt()))
                        .toList(),
                me.sessionExpiresAt());
    }

    /** Sorted by code, so the response is stable and a client can diff two calls meaningfully. */
    private static List<Permission> permissions(Map<String, Scope> effective) {
        return effective.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> new Permission(entry.getKey(), entry.getValue().name()))
                .toList();
    }

    /** @param expiresAt {@code null} is permanent; a date is cover for someone's absence */
    public record Role(String code, Instant expiresAt) {}

    public record Team(UUID id, String name, String timezone) {

        static Team of(TeamRepository.TeamRef team) {
            return team == null ? null : new Team(team.id(), team.name(), team.timezone());
        }
    }

    public record Membership(UUID id, boolean isPrimary) {}

    public record Permission(String code, String scope) {}

    /** An approved break-glass grant in force right now (RB-BR-04), so the UI can show the banner. */
    public record Grant(UUID id, UUID clientId, Instant expiresAt) {}
}
