package com.client360.interaction.client;

import com.client360.common.api.ApiException;
import com.client360.common.api.ErrorCodes;
import com.client360.common.security.CurrentUsers;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Asks client-service — the authorization authority — whether the caller may act on a client
 * (SPEC.md §3.1). This service owns no client table and must never read one.
 *
 * <p>The caller's own bearer token is relayed rather than a service credential. The decision is
 * about the human making the request, so their identity has to survive the hop; a service account
 * would flatten every manager into one all-seeing principal and quietly discard scope.
 *
 * <p>ER-01 passes through unchanged: a client outside the caller's scope is {@code 404} there and
 * {@code 404} here. Translating it to {@code 403} on the way back would leak, through this
 * service, exactly what the other one refused to say.
 */
@Component
public class ClientAccessClient {

    private static final Logger log = LoggerFactory.getLogger(ClientAccessClient.class);
    private static final String CLIENT_NOT_FOUND = "CLIENT_NOT_FOUND";

    private final RestClient rest;

    public ClientAccessClient(
            RestClient.Builder builder,
            @Value("${client360.client-service.base-url}") String baseUrl,
            @Value("${client360.client-service.timeout:2s}") Duration timeout) {
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(org.springframework.boot.web.client.ClientHttpRequestFactories.get(
                        ClientHttpRequestFactorySettings.DEFAULTS
                                .withConnectTimeout(timeout)
                                .withReadTimeout(timeout)))
                .build();
    }

    /**
     * @throws ApiException {@code 404 CLIENT_NOT_FOUND} when the client is absent or out of scope;
     *     {@code 403} when the permission is not held at any scope; {@code 503} when
     *     client-service cannot answer — an authorization question with no answer is not a yes
     */
    public ClientAccessView require(UUID clientId, String permission) {
        try {
            return rest.get()
                    .uri("/api/v1/internal/clients/{clientId}/access?permission={permission}", clientId, permission)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw translate(response.getStatusCode(), clientId, permission);
                    })
                    .body(ClientAccessView.class);
        } catch (ResourceAccessException e) {
            log.error("client-service unreachable while authorizing {} on client {}", permission, clientId);
            throw ApiException.dependencyUnavailable("Cannot reach client-service. Retry shortly.");
        }
    }

    private static ApiException translate(HttpStatusCode status, UUID clientId, String permission) {
        if (status.value() == 404) {
            return ApiException.notFound(CLIENT_NOT_FOUND, "Client not found or not in your scope.");
        }
        if (status.value() == 403) {
            return ApiException.forbidden(
                            ErrorCodes.PERMISSION_DENIED, "This action requires the " + permission + " permission.")
                    .detail("permission", permission);
        }
        // 400 here means this service asked a malformed question — its bug, not the caller's.
        log.error("client-service rejected the access query for client {} with {}", clientId, status);
        return new ApiException(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.INTERNAL_ERROR,
                "Authorization could not be resolved. Quote the requestId when contacting support.");
    }

    private static String bearer() {
        return "Bearer "
                + CurrentUsers.bearerToken().orElseThrow(() -> new IllegalStateException("No bearer token to relay"));
    }

    /** Spring Boot does not add a {@link RestClient.Builder} unless something asks for one. */
    @Component
    static class Customizer implements RestClientCustomizer {

        @Override
        public void customize(RestClient.Builder builder) {
            // Nothing global yet; the bean exists so the builder is auto-configured.
        }
    }
}
