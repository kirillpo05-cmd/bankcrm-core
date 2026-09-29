package com.client360.client.security;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (RB-BR-12: bcrypt, cost 12).
 *
 * <p>A {@link DelegatingPasswordEncoder} rather than a bare {@code BCryptPasswordEncoder} for two
 * reasons: stored hashes carry their algorithm as a {@code {bcrypt}} prefix, so raising the cost
 * later does not invalidate every existing password, and the local seed's {@code {noop}} hashes stay
 * verifiable without a second code path. {@link SeedPasswordGuard} is what keeps that convenience
 * from reaching a deployment.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    /**
     * RB-BR-12's cost. Configurable only downwards for tests: bcrypt at cost 12 is roughly 250 ms
     * by design — that is where §9.3's constant response time comes from — and a suite that logs in
     * a hundred times would spend half a minute on key stretching alone. The guard against a
     * deployment quietly running at cost 4 is that the default here is 12 and nothing in any
     * deployed configuration sets it.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${client360.security.password.bcrypt-cost:12}") int cost) {
        String encoding = "bcrypt";
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(
                encoding, Map.of(encoding, new BCryptPasswordEncoder(cost), "noop", NoopEncoder.INSTANCE));
        // A hash with no {id} prefix is not a legacy format to be guessed at — it is a row written
        // by something that did not go through this encoder. Refuse it rather than try bcrypt.
        encoder.setDefaultPasswordEncoderForMatches(NoopEncoder.REFUSE);
        return encoder;
    }

    /**
     * Spring's own {@code NoOpPasswordEncoder} is deprecated for exactly the right reason, so this
     * spells it out rather than suppressing the warning: the only value it has is letting
     * {@code db/seed/local} hold readable passwords, and {@link SeedPasswordGuard} refuses to start
     * a service that can reach one outside the local profile.
     */
    private enum NoopEncoder implements PasswordEncoder {
        INSTANCE {
            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return java.security.MessageDigest.isEqual(
                        rawPassword.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        encodedPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        },
        /** Matches nothing, for a stored hash whose format is unknown. */
        REFUSE {
            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return false;
            }
        };

        @Override
        public String encode(CharSequence rawPassword) {
            throw new UnsupportedOperationException("Passwords are only ever encoded with bcrypt (RB-BR-12)");
        }
    }
}
