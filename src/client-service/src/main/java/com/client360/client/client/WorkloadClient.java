package com.client360.client.client;

import com.client360.common.api.ApiException;
import com.client360.common.security.CurrentUsers;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks interaction-service what a user still has open, for RB-BR-07's deactivation blockers.
 *
 * <p>Unlike the card's timeline panel, an unreachable callee here is <strong>not</strong> degraded
 * into a blank. A deactivation that went ahead because the ticket count could not be fetched would
 * strand open tickets with an assignee who no longer exists, and nothing would ever report it —
 * so the failure is a {@code 503} and the admin tries again. Refusing is recoverable; a stranded
 * ticket is found months later by a customer complaint.
 */
@Component
public class WorkloadClient {

    private static final Logger log = LoggerFactory.getLogger(WorkloadClient.class);

    private final RestClient rest;

    public WorkloadClient(
            RestClient.Builder builder,
            @Value("${client360.interaction-service.base-url}") String baseUrl,
            @Value("${client360.interaction-service.timeout:2s}") Duration timeout) {
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(timeout)
                                .withReadTimeout(timeout)))
                .build();
    }

    /**
     * @throws ApiException {@code 503 DEPENDENCY_UNAVAILABLE} when the count cannot be obtained
     */
    public int openTickets(UUID userId) {
        try {
            OpenWork work = rest.get()
                    .uri("/api/v1/internal/users/{id}/open-work", userId)
                    // The caller's own token: the decision is about the human doing the deactivating,
                    // so their identity survives the hop, as it does on every other internal call.
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(OpenWork.class);
            return work == null ? 0 : work.openTickets();
        } catch (RestClientException e) {
            log.warn("interaction-service unavailable while counting open work for {}", userId);
            throw ApiException.dependencyUnavailable(
                    "Cannot confirm whether this user still has open tickets. Try again shortly.");
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record OpenWork(int openTickets) {}

    private static String bearer() {
        return "Bearer "
                + CurrentUsers.bearerToken().orElseThrow(() -> new IllegalStateException("No bearer token to relay"));
    }
}
