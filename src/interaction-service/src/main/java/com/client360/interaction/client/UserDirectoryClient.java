package com.client360.interaction.client;

import com.client360.common.security.CurrentUsers;
import com.client360.interaction.api.UserSummary;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves staff display names through client-service, which owns the user table (§3.1).
 *
 * <p>Unlike authorization, a missing name is not a reason to fail the request. A timeline that
 * renders "Adam Nowak" is better than one that renders an id, and both are better than an error
 * page — so an unreachable directory degrades to ids and logs, rather than taking the feed down
 * with it.
 */
@Component
public class UserDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(UserDirectoryClient.class);

    private final RestClient rest;

    public UserDirectoryClient(
            RestClient.Builder builder,
            @Value("${client360.client-service.base-url}") String baseUrl,
            @Value("${client360.client-service.timeout:2s}") Duration timeout) {
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(timeout)
                                .withReadTimeout(timeout)))
                .build();
    }

    /** @return id → summary; ids that could not be resolved map to a summary with a null name */
    public Map<UUID, UserSummary> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<UUID> distinct = ids.stream().distinct().toList();
        try {
            List<DirectoryUser> found = rest.get()
                    .uri(uri -> uri.path("/api/v1/internal/users")
                            .queryParam("ids", distinct.toArray())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DirectoryUser>>() {});
            Map<UUID, UserSummary> byId = found == null
                    ? Map.of()
                    : found.stream().collect(Collectors.toMap(DirectoryUser::id, DirectoryUser::toSummary));
            return distinct.stream()
                    .collect(Collectors.toMap(
                            Function.identity(), id -> byId.getOrDefault(id, new UserSummary(id, null))));
        } catch (RestClientException e) {
            log.warn("user directory unavailable; rendering {} author(s) as ids", distinct.size());
            return distinct.stream().collect(Collectors.toMap(Function.identity(), id -> new UserSummary(id, null)));
        }
    }

    /**
     * Every team in the caller's {@code TEAM} scope, for the cross-client feed (IL-US-07) and the
     * team ticket queue.
     *
     * <p>A set, not one team. RB-BR-02 has always said "teams where the user is supervisor or an
     * active member", and since client-service gained {@code team_members} that can be more than one:
     * a supervisor covering two branches supervises both. This used to resolve to the single
     * {@code primary_team_id} that {@code GET /internal/users} returns, which silently showed such a
     * supervisor half their own book — a feed missing rows looks exactly like a quiet week.
     *
     * <p>Asked of client-service rather than read from the token's {@code teamIds} claim: membership
     * changes, and a claim minted an hour ago would scope a supervisor to a team they have left.
     *
     * @return empty when the directory is unreachable or the caller has no team — and empty must be
     *     read as "no team", never as "every team": a feed shows nothing rather than everything
     */
    public Set<UUID> scopeTeamsOf() {
        try {
            ScopeView scope = rest.get()
                    .uri("/api/v1/internal/me/teams")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(ScopeView.class);
            return scope == null || scope.teamIds() == null ? Set.of() : Set.copyOf(scope.teamIds());
        } catch (RestClientException e) {
            log.warn("client-service unavailable while resolving the caller's team scope");
            return Set.of();
        }
    }

    /** What {@code GET /internal/me/teams} answers. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ScopeView(List<UUID> teamIds, UUID primaryTeamId) {}

    /** What {@code GET /internal/users} answers. Projected to {@link UserSummary} for responses. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectoryUser(UUID id, String fullName, UUID teamId) {
        UserSummary toSummary() {
            return new UserSummary(id, fullName);
        }
    }

    public UserSummary byId(UUID id) {
        return byIds(List.of(id)).getOrDefault(id, new UserSummary(id, null));
    }

    private static String bearer() {
        return "Bearer "
                + CurrentUsers.bearerToken().orElseThrow(() -> new IllegalStateException("No bearer token to relay"));
    }
}
