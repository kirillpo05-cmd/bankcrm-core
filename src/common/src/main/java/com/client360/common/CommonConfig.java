package com.client360.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Picked up by each service's component scan of {@code com.client360.common}. Scheduling drives the
 * outbox relay (§3.4) and the idempotency purge (§4.6).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CommonConfig {}
