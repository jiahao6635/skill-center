package com.iflytek.skillhub.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * AWS SDK credentials provider that resolves STS credentials from Alibaba Cloud ECS instance
 * metadata service (IMDS).
 *
 * <p>On Alibaba Cloud ECS instances with a RAM Role attached, temporary STS credentials are
 * available at:
 * <pre>
 *   http://100.100.100.200/latest/meta-data/ram/security-credentials/{roleName}
 * </pre>
 *
 * <p>The response JSON format from Alibaba Cloud:
 * <pre>{@code
 * {
 *   "Code": "Success",
 *   "AccessKeyId": "STS.xxxx",
 *   "AccessKeySecret": "xxxx",
 *   "SecurityToken": "xxxx",
 *   "Expiration": "2026-07-08T12:00:00Z"
 * }
 * }</pre>
 *
 * <p>Credentials are cached and refreshed 5 minutes before expiration.
 */
class AlibabaCloudEcsCredentialsProvider implements AwsCredentialsProvider {
    private static final Logger log = LoggerFactory.getLogger(AlibabaCloudEcsCredentialsProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String metadataEndpoint;
    private final String roleName;
    private final HttpClient httpClient;

    private volatile AwsSessionCredentials cachedCredentials;
    private volatile Instant expirationTime;

    AlibabaCloudEcsCredentialsProvider(String metadataEndpoint, String roleName) {
        this.metadataEndpoint = metadataEndpoint.endsWith("/")
                ? metadataEndpoint.substring(0, metadataEndpoint.length() - 1)
                : metadataEndpoint;
        this.roleName = roleName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Override
    public AwsCredentials resolveCredentials() {
        AwsSessionCredentials current = cachedCredentials;
        Instant currentExpiration = expirationTime;

        if (current != null && currentExpiration != null && Instant.now().isBefore(currentExpiration.minus(REFRESH_MARGIN))) {
            return current;
        }

        synchronized (this) {
            // Double-check after acquiring lock
            current = cachedCredentials;
            currentExpiration = expirationTime;
            if (current != null && currentExpiration != null && Instant.now().isBefore(currentExpiration.minus(REFRESH_MARGIN))) {
                return current;
            }
            return refreshCredentials();
        }
    }

    private AwsSessionCredentials refreshCredentials() {
        String url = metadataEndpoint + "/latest/meta-data/ram/security-credentials/" + roleName;
        log.debug("Refreshing STS credentials from Alibaba Cloud IMDS: {}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("IMDS returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = MAPPER.readTree(response.body());

            String code = node.path("Code").asText("");
            if (!"Success".equals(code)) {
                throw new IOException("IMDS response code: " + code);
            }

            String accessKeyId = node.path("AccessKeyId").asText(null);
            String accessKeySecret = node.path("AccessKeySecret").asText(null);
            String securityToken = node.path("SecurityToken").asText(null);
            String expiration = node.path("Expiration").asText(null);

            if (accessKeyId == null || accessKeySecret == null || securityToken == null) {
                throw new IOException("IMDS response missing required fields");
            }

            AwsSessionCredentials credentials = AwsSessionCredentials.create(accessKeyId, accessKeySecret, securityToken);
            this.cachedCredentials = credentials;
            this.expirationTime = expiration != null ? Instant.parse(expiration) : Instant.now().plus(Duration.ofHours(1));

            log.info("Successfully refreshed STS credentials from Alibaba Cloud IMDS (expires: {})", this.expirationTime);
            return credentials;

        } catch (IOException e) {
            throw SdkClientException.builder()
                    .message("Failed to retrieve credentials from Alibaba Cloud ECS IMDS at " + url)
                    .cause(e)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SdkClientException.builder()
                    .message("Interrupted while retrieving credentials from Alibaba Cloud ECS IMDS")
                    .cause(e)
                    .build();
        }
    }
}
