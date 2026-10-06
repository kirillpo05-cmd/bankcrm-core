package com.client360.audit.export;

import com.client360.common.api.ApiException;
import java.io.InputStream;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * The export bucket, behind three methods (SPEC.md §8.3, AT-EC-09).
 *
 * <p>Bytes never travel back through this service. The file goes straight to the bucket as it is
 * generated and the download is a pre-signed link the client follows itself — streaming a
 * multi-million-row export through a request thread would hold one for the length of somebody's
 * connection and put the audit log in this service's heap, which is the one place AT-BR-04 spends
 * its effort keeping clean.
 */
@Component
@EnableConfigurationProperties(ExportProperties.class)
public class ExportStore {

    private static final Logger log = LoggerFactory.getLogger(ExportStore.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final ExportProperties properties;

    public ExportStore(S3Client s3, S3Presigner presigner, ExportProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    /**
     * Uploads the export as it is produced.
     *
     * <p>{@code contentLength} is required by the SDK's synchronous streaming body, which is why
     * the generator writes to a temporary file first and sends its length here: the alternative is
     * buffering the whole export to learn its size, which is exactly what AT-EC-09 forbids.
     */
    public void put(String key, InputStream content, long contentLength, String contentType) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .contentEncoding("gzip")
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (RuntimeException e) {
            // Never the exception's message: it can carry the endpoint, the key and a signature.
            log.error("export upload failed: {}", e.getClass().getSimpleName());
            throw ApiException.dependencyUnavailable("Export storage is unavailable. Retry shortly.");
        }
    }

    /** A link valid for {@link ExportProperties#linkTtl} — fifteen minutes (§8.3). */
    public URI presignedGet(String key, String filename) {
        try {
            return presigner
                    .presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(properties.linkTtl())
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(properties.bucket())
                                    .key(key)
                                    .responseContentDisposition("attachment; filename=\"" + sanitize(filename) + "\"")
                                    .build())
                            .build())
                    .url()
                    .toURI();
        } catch (Exception e) {
            log.error("could not pre-sign an export link: {}", e.getClass().getSimpleName());
            throw ApiException.dependencyUnavailable("Export storage is unavailable. Retry shortly.");
        }
    }

    /**
     * Deletes an expired export's object, or the half-written one of a job that failed.
     *
     * <p>Quiet by design: the job row records what happened either way, and an object nothing
     * points at is litter rather than a failure to report to whoever is reading the response.
     */
    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException e) {
            log.warn("orphaned export object left behind: {}", e.getClass().getSimpleName());
        }
    }

    /** A filename reaches an HTTP header here, so quotes and control characters cannot travel. */
    private static String sanitize(String filename) {
        return filename.replaceAll("[\\p{Cntrl}\"\\\\]", "_");
    }

    /**
     * Wired by hand rather than by a starter: the SDK's default credential chain would look for
     * instance metadata and environment profiles, and this service must use exactly the key it was
     * configured with.
     */
    @org.springframework.context.annotation.Configuration
    static class Clients {

        @Bean
        S3Client exportS3Client(ExportProperties properties) {
            var builder = S3Client.builder()
                    .region(Region.of(properties.region()))
                    .credentialsProvider(credentials(properties))
                    .httpClient(UrlConnectionHttpClient.create())
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(properties.pathStyle())
                            .build());
            if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
                builder.endpointOverride(URI.create(properties.endpoint()));
            }
            return builder.build();
        }

        @Bean
        S3Presigner exportS3Presigner(ExportProperties properties) {
            S3Presigner.Builder builder = S3Presigner.builder()
                    .region(Region.of(properties.region()))
                    .credentialsProvider(credentials(properties))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(properties.pathStyle())
                            .build());
            if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
                builder.endpointOverride(URI.create(properties.endpoint()));
            }
            return builder.build();
        }

        private static StaticCredentialsProvider credentials(ExportProperties properties) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        }
    }
}
