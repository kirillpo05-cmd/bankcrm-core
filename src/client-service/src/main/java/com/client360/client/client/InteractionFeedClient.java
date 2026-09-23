package com.client360.client.client;

import com.client360.common.web.KeysetPage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the head of a client's timeline from interaction-service, for the card's first paint
 * (SPEC.md §5.3).
 *
 * <p>This is the first call that runs <em>from</em> client-service <em>to</em> another service, and
 * it is display data only. Authorization still travels the other way: interaction-service asks this
 * service whether the caller may read the client, exactly as it does for a direct timeline request.
 * So one summary request is three hops — client-service, interaction-service, and back here for the
 * access check — which is why the timeout below is not optional. It is the budget §5.3 sets for a
 * sub-resource, and it is what stops a slow or saturated interaction-service from holding a request
 * thread here: the panel degrades instead.
 *
 * <p>The caller's own bearer token is relayed, never a service credential. The timeline applies
 * IL-BR-09 — private notes belong to their author — against the human who asked, and a service
 * account would flatten every caller into one principal that sees everything.
 */
@Component
public class InteractionFeedClient {

    private final RestClient rest;

    public InteractionFeedClient(
            RestClient.Builder builder,
            @Value("${client360.interaction-service.base-url}") String baseUrl,
            @Value("${client360.interaction-service.timeout:800ms}") Duration timeout) {
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(timeout)
                                .withReadTimeout(timeout)))
                .build();
    }

    /**
     * The newest {@code limit} interactions the caller may see.
     *
     * <p>Throws rather than returning an empty list on failure. "Could not load" and "there are
     * none" are different answers, and only the caller of this method knows that the first one
     * belongs in {@code degraded[]}.
     */
    public List<RecentInteraction> recent(UUID clientId, int limit, String bearerToken) {
        KeysetPage<RecentInteraction> page = rest.get()
                .uri("/api/v1/clients/{clientId}/interactions?limit={limit}", clientId, limit)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .body(new ParameterizedTypeReference<KeysetPage<RecentInteraction>>() {});
        return page == null ? List.of() : page.content();
    }
}
