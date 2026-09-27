package com.client360.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.client360.common.idempotency.IdempotencyService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Attachments end to end (SPEC.md §6.3, IL-US-06), against a real MinIO.
 *
 * <p>A doubled object store would agree with whatever this code assumed about pre-signing, and the
 * one thing worth proving here is that a link issued by the service is a link a browser can
 * actually follow — so the test follows it.
 */
@Import(AbstractInteractionIntegrationTest.Doubles.class)
class AttachmentIntegrationTest extends AbstractInteractionIntegrationTest {

    /**
     * A real S3 server, pinned to the same image docker-compose.yml runs so the tests and the local
     * stack cannot drift onto different S3 behaviour.
     *
     * <p>LocalStack rather than MinIO: MinIO stopped publishing pullable images — docker.io and
     * quay.io both answer 401 — so CI could not fetch one and neither could a fresh clone. What
     * makes the swap cost nothing is that this service speaks the S3 API through the AWS SDK rather
     * than a MinIO client, so only the endpoint moves.
     */
    private static final LocalStackContainer S3 = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private static final String BUCKET = "client360-attachments";

    static {
        S3.start();
        createBucket();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add(
                "client360.s3.endpoint",
                () -> S3.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("client360.s3.bucket", () -> BUCKET);
        registry.add("client360.s3.access-key", S3::getAccessKey);
        registry.add("client360.s3.secret-key", S3::getSecretKey);
    }

    private String interactionId;

    @BeforeEach
    void anInteractionToAttachTo() throws Exception {
        interactionId = createInteraction();
    }

    @Nested
    class Uploading {

        @Test
        void storesTheFileAndReportsItUnscanned_IL_US_06() throws Exception {
            upload("statement.pdf", "application/pdf", "%PDF-1.4 statement")
                    .andExpect(status().isCreated())
                    .andExpect(header().exists(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.filename").value("statement.pdf"))
                    .andExpect(jsonPath("$.contentType").value("application/pdf"))
                    // Nothing scans yet, and the API says so rather than implying the file is safe.
                    .andExpect(jsonPath("$.scan").value("PENDING"));
        }

        /** The checksum is what tells a file that came back different from one that always was. */
        @Test
        void recordsTheChecksumOfWhatWasStored() throws Exception {
            String content = "%PDF-1.4 statement";
            upload("statement.pdf", "application/pdf", content)
                    .andExpect(jsonPath("$.checksumSha256").value(sha256Hex(content)));
        }

        @Test
        void refusesAnUnsupportedType_ck_att_type() throws Exception {
            upload("payload.exe", "application/x-msdownload", "MZ")
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_TYPE_UNSUPPORTED"));
        }

        @Test
        void refusesAFileOverTenMegabytes_ck_att_size() throws Exception {
            upload("big.pdf", "application/pdf", "x".repeat(10 * 1024 * 1024 + 1))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_TOO_LARGE"));
        }

        @Test
        void refusesAnEmptyFile() throws Exception {
            upload("empty.pdf", "application/pdf", "").andExpect(status().isBadRequest());
        }

        /**
         * §6.2.3: five per interaction, enforced in the service inside the upload transaction
         * because a per-parent count cannot be a CHECK.
         */
        @Test
        void refusesTheSixthAttachment_ATTACHMENT_LIMIT_REACHED() throws Exception {
            for (int i = 1; i <= 5; i++) {
                upload("page" + i + ".pdf", "application/pdf", "%PDF page " + i).andExpect(status().isCreated());
            }
            upload("page6.pdf", "application/pdf", "%PDF page 6")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.details[0].code").value("ATTACHMENT_LIMIT_REACHED"));
        }
    }

    /** §6.3: the detail lists what is attached; the timeline carries only a count (IL-BR-11). */
    @Test
    void theInteractionDetailListsItsAttachments_IL_US_06() throws Exception {
        upload("statement.pdf", "application/pdf", "%PDF-1.4 statement").andExpect(status().isCreated());
        upload("id-scan.png", "image/png", "PNG scan").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/interactions/{id}", interactionId)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andExpect(jsonPath("$.attachments[0].filename").value("statement.pdf"))
                .andExpect(jsonPath("$.attachments[0].scan").value("PENDING"))
                // No URL in the listing: a link is issued per download and audited (IL-BR-11).
                .andExpect(jsonPath("$.attachments[0].url").doesNotHaveJsonPath());

        mvc.perform(get("/api/v1/clients/{id}/interactions", CLIENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                .andExpect(jsonPath("$.content[0].attachmentCount").value(2));
    }

    /** §4.10: twenty an hour, per user. The other half of the limit — the size — is above. */
    @Test
    void refusesTheTwentyFirstUploadWithinTheHour_4_10() throws Exception {
        for (int i = 1; i <= 20; i++) {
            // A fresh interaction each time, so the five-per-interaction limit is not what refuses.
            interactionId = createInteraction();
            upload("page" + i + ".pdf", "application/pdf", "%PDF page " + i).andExpect(status().isCreated());
        }
        interactionId = createInteraction();
        upload("page21.pdf", "application/pdf", "%PDF page 21")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Nested
    class Downloading {

        /** §6.3: a file nobody has scanned is not served on the strength of who uploaded it. */
        @Test
        void refusesWhileUnscanned_ATTACHMENT_SCAN_PENDING() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            mvc.perform(get("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_SCAN_PENDING"));
        }

        @Test
        void refusesAnInfectedFile_ATTACHMENT_INFECTED() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            markScanned(id, "INFECTED");
            mvc.perform(get("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ATTACHMENT_INFECTED"));
        }

        /**
         * The point of the whole design: the service hands back a link, and the bytes travel from
         * the bucket to the browser without passing through a request thread here. So the test
         * follows the link and checks the bytes.
         */
        @Test
        void issuesALinkThatActuallyServesTheFile_IL_US_06() throws Exception {
            String content = "%PDF-1.4 statement";
            String id = uploadedId("statement.pdf", "application/pdf", content);
            markScanned(id, "CLEAN");

            String location = mvc.perform(get("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isFound())
                    // A signed URL must not linger in a shared cache.
                    .andExpect(header().string(
                                    HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                    .andReturn()
                    .getResponse()
                    .getHeader(HttpHeaders.LOCATION);

            HttpResponse<String> fetched = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create(location)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(fetched.statusCode()).isEqualTo(200);
            assertThat(fetched.body()).isEqualTo(content);
            // The browser saves it under the name the manager uploaded, not the opaque key.
            assertThat(fetched.headers().firstValue("content-disposition"))
                    .hasValueSatisfying(value -> assertThat(value).contains("statement.pdf"));
        }

        /** IL-BR-11: opening a document is a disclosure, and one is recorded per link issued. */
        @Test
        void auditsTheDisclosure_IL_BR_11() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            markScanned(id, "CLEAN");
            jdbc.sql("DELETE FROM interaction.outbox_events").update();

            mvc.perform(get("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isFound());
            assertThat(countEvents("interaction.read_sensitive")).isEqualTo(1);
        }
    }

    @Nested
    class Deleting {

        @Test
        void theUploaderMayRemoveItInsideTheWindow() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            mvc.perform(delete("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNoContent());

            // Soft delete: the row is still there, it has simply stopped being served.
            assertThat(jdbc.sql("SELECT count(*) FROM interaction.interaction_attachments"
                                    + " WHERE id = CAST(:id AS uuid) AND deleted_at IS NOT NULL")
                            .param("id", id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }

        /** Somebody else's attachment is not theirs to remove, and ER-01 shapes the answer. */
        @Test
        void anotherManagerCannotRemoveIt_ER_01() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            mvc.perform(delete("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(MARTA_LEWANDOWSKA)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aDeletedAttachmentIsNoLongerDownloadable() throws Exception {
            String id = uploadedId("statement.pdf", "application/pdf", "%PDF-1.4 statement");
            markScanned(id, "CLEAN");
            mvc.perform(delete("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/interactions/{i}/attachments/{a}", interactionId, id)
                            .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)))
                    .andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions upload(String filename, String contentType, String content) throws Exception {
        return mvc.perform(multipart("/api/v1/interactions/{id}/attachments", interactionId)
                .file(new MockMultipartFile("file", filename, contentType, content.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK)));
    }

    private String uploadedId(String filename, String contentType, String content) throws Exception {
        return jsonField(
                upload(filename, contentType, content)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    /** No scanner is wired yet (§6.2.3), so the tests move the status the way one eventually will. */
    private void markScanned(String id, String status) {
        jdbc.sql("UPDATE interaction.interaction_attachments"
                        + " SET scan = CAST(:status AS interaction.scan_status), scanned_at = now()"
                        + " WHERE id = CAST(:id AS uuid)")
                .param("status", status)
                .param("id", id)
                .update();
    }

    private String createInteraction() throws Exception {
        return jsonField(
                mvc.perform(post("/api/v1/clients/{id}/interactions", CLIENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, bearerFor(ADAM_NOWAK))
                                .header(
                                        IdempotencyService.HEADER,
                                        UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"type":"CALL","direction":"OUTBOUND","subject":"Statement request",
                                         "body":"Client asked for a statement.","occurredAt":"2026-09-07T09:00:00Z"}"""))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private static void createBucket() {
        try (var client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(S3.getEndpointOverride(LocalStackContainer.Service.S3))
                .region(software.amazon.awssdk.regions.Region.of(S3.getRegion()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                S3.getAccessKey(), S3.getSecretKey())))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
        }
    }

    private static String sha256Hex(String content) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
