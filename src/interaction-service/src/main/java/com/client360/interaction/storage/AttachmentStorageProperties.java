package com.client360.interaction.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where interaction attachments live (SPEC.md §6.2.3, IL-US-06).
 *
 * <p>Addressed through the S3 API, which is MinIO locally and a real bucket in a deployed
 * environment — the only difference is {@code endpoint}.
 *
 * @param endpoint the S3 endpoint. Empty in a deployed environment, where the region resolves it
 * @param linkTtl how long a download link stays valid. §6.3 says 60 seconds: long enough for a
 *     browser to follow a redirect, short enough that a link copied out of a log or a chat is
 *     already dead. It is not a share mechanism
 * @param pathStyle MinIO serves buckets as a path segment; real S3 uses a virtual host
 */
@ConfigurationProperties("client360.s3")
public record AttachmentStorageProperties(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("60s") Duration linkTtl,
        @DefaultValue("true") boolean pathStyle) {}
