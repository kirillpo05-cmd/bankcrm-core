package com.client360.interaction.client;

import com.client360.common.security.CurrentUsers;
import com.client360.interaction.api.UserSummary;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
            List<UserSummary> found = rest.get()
                    .uri(uri -> uri.path("/api/v1/internal/users")
                            .queryParam("ids", distinct.toArray())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<UserSummary>>() {});
            Map<UUID, UserSummary> byId = found == null
                    ? Map.of()
                    : found.stream().collect(Collectors.toMap(UserSummary::id, Function.identity()));
            return distinct.stream()
                    .collect(Collectors.toMap(
                            Function.identity(), id -> byId.getOrDefault(id, new UserSummary(id, null))));
        } catch (RestClientException e) {
            log.warn("user directory unavailable; rendering {} author(s) as ids", distinct.size());
            return distinct.stream().collect(Collectors.toMap(Function.identity(), id -> new UserSummary(id, null)));
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
