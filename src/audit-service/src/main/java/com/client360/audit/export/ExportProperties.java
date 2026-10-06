package com.client360.audit.export;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where audit exports live and how long their links last (SPEC.md §8.3, AT-US-04).
 *
 * @param linkTtl §8.3: fifteen minutes. Longer than an attachment's sixty seconds because an
 *     export is a file a compliance officer saves and attaches to a regulatory response rather
 *     than a thumbnail a browser fetches immediately — but still short, because the link is the
 *     only thing standing between a URL in somebody's clipboard and the audit log's contents
 * @param retention §8.3: how long the file itself is kept. Past {@code expires_at} the object is
 *     deleted and the job answers {@code 410}; a seven-year log does not need seven years of
 *     exports of itself lying around in a bucket
 * @param maxRows AT-EC-09's ceiling. An export of five million rows is a legitimate regulatory
 *     request; one of fifty million is a filter somebody forgot to set
 */
@ConfigurationProperties("client360.audit.export")
public record ExportProperties(
        String endpoint,
        String bucket,
        String accessKey,
        String secretKey,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("15m") Duration linkTtl,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("5000000") long maxRows,
        @DefaultValue("true") boolean pathStyle) {}
