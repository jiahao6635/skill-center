package com.iflytek.skillhub.service.sharelink;

import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.config.SkillShareLinkProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * SSRF-constrained HTTP client for share-link metadata and zip downloads.
 *
 * <p>Redirects are followed only after each hop is re-validated against the same
 * host allowlist, HTTPS, and public-address rules.
 */
@Component
public class RemoteSkillPackageDownloader {

    private static final Logger log = LoggerFactory.getLogger(RemoteSkillPackageDownloader.class);
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final String USER_AGENT = "SkillHub-ShareLink/1.0";

    private final long maxPackageSize;
    private final int maxRedirects;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final boolean verifyResolvedAddress;

    @Autowired
    public RemoteSkillPackageDownloader(
            SkillPublishProperties publishProperties,
            SkillShareLinkProperties shareLinkProperties) {
        this(
                publishProperties,
                shareLinkProperties,
                HttpClient.newBuilder()
                        .connectTimeout(shareLinkProperties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()
        );
    }

    RemoteSkillPackageDownloader(
            SkillPublishProperties publishProperties,
            SkillShareLinkProperties shareLinkProperties,
            HttpClient httpClient) {
        this(publishProperties, shareLinkProperties, httpClient, true);
    }

    RemoteSkillPackageDownloader(
            SkillPublishProperties publishProperties,
            SkillShareLinkProperties shareLinkProperties,
            HttpClient httpClient,
            boolean verifyResolvedAddress) {
        this.maxPackageSize = publishProperties.getMaxPackageSize();
        this.maxRedirects = Math.max(0, shareLinkProperties.getMaxRedirects());
        this.requestTimeout = shareLinkProperties.getRequestTimeout();
        this.httpClient = httpClient;
        this.verifyResolvedAddress = verifyResolvedAddress;
    }

    public byte[] downloadZip(URI uri, Set<String> allowedHosts) {
        HttpResponse<InputStream> response = sendFollowingRedirects(uri, allowedHosts, "GET", null, true);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new DomainBadRequestException(
                        "error.skill.publish.shareLink.downloadFailed",
                        String.valueOf(response.statusCode()));
            }
            return readBounded(body, uri);
        } catch (IOException ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.downloadFailed", ex.getMessage());
        }
    }

    public String getJson(URI uri, Set<String> allowedHosts, Optional<String> bearerToken) {
        HttpResponse<InputStream> response = sendFollowingRedirects(
                uri,
                allowedHosts,
                "GET",
                bearerToken.filter(token -> !token.isBlank()).orElse(null),
                false
        );
        try (InputStream body = response.body()) {
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new DomainBadRequestException("error.skill.publish.shareLink.authRequired");
            }
            if (response.statusCode() == 404) {
                throw new DomainBadRequestException("error.skill.publish.shareLink.notFound");
            }
            if (response.statusCode() != 200) {
                throw new DomainBadRequestException(
                        "error.skill.publish.shareLink.metadataFailed",
                        String.valueOf(response.statusCode()));
            }
            byte[] bytes = readBounded(body, uri);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.metadataFailed", ex.getMessage());
        }
    }

    public void assertAllowedUrl(URI uri, Set<String> allowedHosts) {
        if (!isAllowedUrl(uri, allowedHosts)) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.hostNotAllowed", safeUrl(uri));
        }
        if (verifyResolvedAddress && resolvesToNonPublicAddress(uri)) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.hostNotAllowed", safeUrl(uri));
        }
    }

    static boolean isAllowedUrl(URI uri, Set<String> allowedHosts) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (isDisallowedHostLiteral(normalizedHost)) {
            return false;
        }
        return matchesAllowedHost(normalizedHost, allowedHosts);
    }

    static boolean matchesAllowedHost(String host, Set<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        for (String allowed : allowedHosts) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            String normalized = allowed.toLowerCase(Locale.ROOT).trim();
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    private HttpResponse<InputStream> sendFollowingRedirects(
            URI initialUri,
            Set<String> allowedHosts,
            String method,
            String bearerToken,
            boolean zipDownload) {
        URI current = initialUri;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            assertAllowedUrl(current, allowedHosts);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(requestTimeout)
                    .header("User-Agent", USER_AGENT)
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (bearerToken != null) {
                builder.header("Authorization", "Bearer " + bearerToken);
                builder.header("Accept", "application/json");
            } else if (zipDownload) {
                builder.header("Accept", "application/zip,application/octet-stream,*/*");
            } else {
                builder.header("Accept", "application/json");
            }
            HttpResponse<InputStream> response = send(builder.build());
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            closeQuietly(response.body());
            String location = firstHeader(response.headers(), "location");
            if (location == null || location.isBlank()) {
                throw new DomainBadRequestException("error.skill.publish.shareLink.downloadFailed", "redirect");
            }
            current = current.resolve(location);
            method = "GET";
            bearerToken = null;
        }
        throw new DomainBadRequestException("error.skill.publish.shareLink.tooManyRedirects");
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.downloadFailed", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DomainBadRequestException("error.skill.publish.shareLink.downloadFailed", "interrupted");
        }
    }

    private byte[] readBounded(InputStream inputStream, URI uri) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> future = executor.submit(() -> readBoundedSync(inputStream));
        try {
            return future.get(Math.max(1, requestTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            closeQuietly(inputStream);
            future.cancel(true);
            log.warn("Timed out while reading share-link body from {} after {}", safeUrl(uri), requestTimeout);
            throw new DomainBadRequestException("error.skill.publish.shareLink.timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closeQuietly(inputStream);
            future.cancel(true);
            throw new DomainBadRequestException("error.skill.publish.shareLink.downloadFailed", "interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof DomainBadRequestException domainBadRequestException) {
                throw domainBadRequestException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new DomainBadRequestException(
                    "error.skill.publish.shareLink.downloadFailed",
                    cause == null ? "read failed" : cause.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    private byte[] readBoundedSync(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxPackageSize) {
                throw new DomainBadRequestException(
                        "error.skill.publish.shareLink.packageTooLarge",
                        String.valueOf(maxPackageSize));
            }
            outputStream.write(buffer, 0, read);
        }
        if (totalRead == 0) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.emptyPackage");
        }
        return outputStream.toByteArray();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
            // Best-effort cleanup after timeout or redirect hop.
        }
    }

    private static boolean isDisallowedHostLiteral(String host) {
        return "localhost".equals(host)
                || "0.0.0.0".equals(host)
                || IPV4_LITERAL.matcher(host).matches()
                || host.contains(":");
    }

    private static boolean resolvesToNonPublicAddress(URI uri) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return true;
        }
    }

    static String safeUrl(URI uri) {
        if (uri == null) {
            return "<null>";
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        return (host == null ? "<unknown-host>" : host) + (path == null ? "" : path);
    }
}
