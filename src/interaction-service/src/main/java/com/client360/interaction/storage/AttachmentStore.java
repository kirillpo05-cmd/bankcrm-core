package com.client360.interaction.storage;

import com.client360.common.api.ApiException;
import java.net.URI;
import java.time.Duration;
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
 * The object store, behind two methods (SPEC.md §6.2.3, IL-US-06).
 *
 * <p>Bytes never travel back through this service. An upload goes straight to the bucket and a
 * download is a short-lived pre-signed link the browser follows itself — streaming a 10 MB file
 * through a request thread would tie one up for the length of somebody's connection, and put
 * customer documents in this service's heap for no reason.
 *
 * <p>The key is opaque and derived from identifiers, never from the filename. A filename comes from
 * the caller and would otherwise decide a path in the bucket.
 */
@Component
@EnableConfigurationProperties(AttachmentStorageProperties.class)
public class AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStore.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AttachmentStorageProperties properties;

    public AttachmentStore(S3Client s3, S3Presigner presigner, AttachmentStorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
    }

    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (RuntimeException e) {
            // Never the exception's message: it can carry the endpoint, the key and a signature.
            log.error("attachment upload failed: {}", e.getClass().getSimpleName());
            throw ApiException.dependencyUnavailable("Attachment storage is unavailable. Retry shortly.");
        }
    }

    /** A link that expires in {@link AttachmentStorageProperties#linkTtl}. */
    public URI presignedGet(String key, String filename) {
        try {
            return presigner
                    .presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(properties.linkTtl())
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(properties.bucket())
                                    .key(key)
                                    // The browser saves it under the name the manager uploaded,
                                    // not under the opaque key.
                                    .responseContentDisposition("attachment; filename=\"" + sanitize(filename) + "\"")
                                    .build())
                            .build())
                    .url()
                    .toURI();
        } catch (Exception e) {
            log.error("could not pre-sign an attachment link: {}", e.getClass().getSimpleName());
            throw ApiException.dependencyUnavailable("Attachment storage is unavailable. Retry shortly.");
        }
    }

    /**
     * Only for the upload that could not be recorded. A soft-deleted attachment keeps its object:
     * the row still points at it, and §6.3's deletion is a product action, not an erasure.
     */
    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (RuntimeException e) {
            // The row was never written, so the object is unreferenced either way. Worth a line,
            // not worth failing the response the caller is already getting.
            log.warn("orphaned attachment object left behind: {}", e.getClass().getSimpleName());
        }
    }

    /** A filename reaches an HTTP header here, so quotes and control characters cannot travel. */
    private static String sanitize(String filename) {
        return filename.replaceAll("[\\p{Cntrl}\"\\\\]", "_");
    }

    Duration linkTtl() {
        return properties.linkTtl();
    }

    /**
     * Wired by hand rather than by a starter: the SDK's default credential chain would look for
     * instance metadata and environment profiles, and this service must use exactly the key it was
     * configured with.
     */
    @org.springframework.context.annotation.Configuration
    static class Clients {

        @Bean
        S3Client s3Client(AttachmentStorageProperties properties) {
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
        S3Presigner s3Presigner(AttachmentStorageProperties properties) {
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

        private static StaticCredentialsProvider credentials(AttachmentStorageProperties properties) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        }
    }
}
