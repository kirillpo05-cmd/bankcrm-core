package com.client360.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Local stand-ins for KMS material ({@code .env.example}: {@code CLIENT360_CRYPTO_DEV_KEY},
 * {@code CLIENT360_CRYPTO_DEV_PEPPER}). Never populated from a file in a deployed environment.
 *
 * @param dataKey base64 of exactly 32 bytes (AES-256)
 * @param pepper base64 of at least 16 bytes (HMAC-SHA256 key for lookup hashes)
 * @param keyVersion the version recorded in {@code key_version} for new ciphertext
 */
@ConfigurationProperties("client360.crypto")
public record CryptoProperties(String dataKey, String pepper, @DefaultValue("1") short keyVersion) {}
