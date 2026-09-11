package com.client360.common.security;

import com.client360.common.web.RequestIdFilter;
import com.client360.common.web.UserMdcFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Stateless bearer-token security shared by every service.
 *
 * <p>Deliberately contains no {@code hasRole(...)} rule: the filter chain only establishes who
 * the caller is. What they may do is decided per request through {@link AccessPolicy}
 * (CLAUDE.md rule 5).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthErrorHandlers authErrors) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                token -> new JwtAuthenticationToken(token, List.of(), token.getSubject())))
                        .authenticationEntryPoint(authErrors)
                        .accessDeniedHandler(authErrors))
                .exceptionHandling(e -> e.authenticationEntryPoint(authErrors).accessDeniedHandler(authErrors))
                // §10.4 security headers. The API serves JSON only, so the CSP forbids everything.
                .headers(headers -> headers.contentSecurityPolicy(
                                csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, ResourceLoader resourceLoader) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(loadPublicKey(properties, resourceLoader))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> defaults = properties.issuer() == null || properties.issuer().isBlank()
                ? JwtValidators.createDefault()
                : JwtValidators.createDefaultWithIssuer(properties.issuer());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, SecurityConfig::subjectIsUserId));
        return decoder;
    }

    /** Stub until v2, which replaces this bean method; see {@link MvpAccessPolicy}. */
    @Bean
    AccessPolicy mvpAccessPolicy() {
        return new MvpAccessPolicy();
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        // Before Spring Security, so rejected requests carry a requestId too.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UserMdcFilter> userMdcFilter() {
        FilterRegistrationBean<UserMdcFilter> registration = new FilterRegistrationBean<>(new UserMdcFilter());
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    /** {@code sub} is a user id; every {@code *_by} and {@code author_id} column depends on it. */
    private static OAuth2TokenValidatorResult subjectIsUserId(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "sub must be a user id", null));
        }
    }

    static RSAPublicKey loadPublicKey(JwtProperties properties, ResourceLoader resourceLoader) {
        String pem = properties.publicKey();
        if ((pem == null || pem.isBlank()) && properties.publicKeyLocation() != null) {
            try (InputStream in = resourceLoader.getResource(properties.publicKeyLocation()).getInputStream()) {
                pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot read client360.security.jwt.public-key-location=" + properties.publicKeyLocation()
                                + " (locally: run scripts/dev-jwt.sh keys)",
                        e);
            }
        }
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "No JWT verification key: set client360.security.jwt.public-key or public-key-location");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("client360.security.jwt public key is not an RSA SubjectPublicKeyInfo", e);
        }
    }
}
