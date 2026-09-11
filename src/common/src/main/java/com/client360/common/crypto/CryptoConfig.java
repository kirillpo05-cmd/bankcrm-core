package com.client360.common.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    /** Replaced by a KMS-backed provider in deployed environments. */
    @Bean
    KeyProvider staticKeyProvider(CryptoProperties properties) {
        return new StaticKeyProvider(properties);
    }
}
