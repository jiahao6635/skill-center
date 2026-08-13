package com.iflytek.skillhub.service.sharelink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.SkillShareLinkProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves QoderWork skill share landing pages into a downloadable zip.
 *
 * <p>Share URLs such as {@code https://qoder.com/link/qoder-work/skill/install?shareId=} are
 * SPA pages. QoderWork itself calls {@code GET /api/v1/skill-links?share_id=} and then
 * downloads {@code download_url}.
 */
@Component
public class QoderWorkShareLinkResolver {

    private static final Logger log = LoggerFactory.getLogger(QoderWorkShareLinkResolver.class);
    private static final Pattern SHARE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");

    private final SkillShareLinkProperties properties;
    private final RemoteSkillPackageDownloader downloader;
    private final ObjectMapper objectMapper;

    public QoderWorkShareLinkResolver(
            SkillShareLinkProperties properties,
            RemoteSkillPackageDownloader downloader,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.downloader = downloader;
        this.objectMapper = objectMapper;
    }

    public boolean supports(URI uri) {
        if (!properties.getQoderwork().isEnabled() || uri == null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!isQoderWebsiteHost(host)) {
            return false;
        }
        return extractShareId(uri).isPresent();
    }

    public byte[] downloadPackage(URI shareUri) {
        String shareId = extractShareId(shareUri)
                .orElseThrow(() -> new DomainBadRequestException("error.skill.publish.shareLink.unsupported"));

        DomainBadRequestException lastFailure = null;
        for (String apiBase : properties.getQoderwork().getApiBases()) {
            if (apiBase == null || apiBase.isBlank()) {
                continue;
            }
            try {
                URI metadataUri = URI.create(trimTrailingSlash(apiBase.trim()) + "/api/v1/skill-links?share_id="
                        + encodeShareId(shareId));
                downloader.assertAllowedUrl(metadataUri, allowedHosts());
                String json = downloader.getJson(
                        metadataUri,
                        allowedHosts(),
                        optionalToken()
                );
                URI downloadUri = extractDownloadUri(json);
                downloader.assertAllowedUrl(downloadUri, allowedHosts());
                log.info("Resolved QoderWork share {} via {}", shareId, metadataUri.getHost());
                return downloader.downloadZip(downloadUri, allowedHosts());
            } catch (DomainBadRequestException ex) {
                lastFailure = ex;
                if ("error.skill.publish.shareLink.authRequired".equals(ex.messageCode())
                        || "error.skill.publish.shareLink.notFound".equals(ex.messageCode())
                        || "error.skill.publish.shareLink.unsupported".equals(ex.messageCode())) {
                    throw ex;
                }
                log.warn("QoderWork metadata lookup failed via {}: {}", apiBase, ex.getMessage());
            } catch (IllegalArgumentException ex) {
                lastFailure = new DomainBadRequestException("error.skill.publish.shareLink.unsupported");
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new DomainBadRequestException("error.skill.publish.shareLink.metadataFailed", "unavailable");
    }

    Optional<String> extractShareId(URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if ("shareId".equalsIgnoreCase(key) || "share_id".equalsIgnoreCase(key)) {
                    String value = java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    return normalizeShareId(value);
                }
            }
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("share".equalsIgnoreCase(segments[i]) || "shareId".equalsIgnoreCase(segments[i])) {
                return normalizeShareId(segments[i + 1]);
            }
        }
        return Optional.empty();
    }

    private URI extractDownloadUri(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode payload = unwrapPayload(root);
            String downloadUrl = firstText(payload, "download_url", "downloadUrl", "file_url", "package_url");
            if (downloadUrl == null || downloadUrl.isBlank()) {
                throw new DomainBadRequestException("error.skill.publish.shareLink.missingDownloadUrl");
            }
            return URI.create(downloadUrl.trim());
        } catch (DomainBadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.metadataFailed", "invalid-json");
        }
    }

    private JsonNode unwrapPayload(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.missingDownloadUrl");
        }
        if (root.hasNonNull("download_url") || root.hasNonNull("downloadUrl")) {
            return root;
        }
        for (String field : new String[] {"data", "result", "payload"}) {
            JsonNode nested = root.get(field);
            if (nested != null && nested.isObject()) {
                return nested;
            }
        }
        return root;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Optional<String> optionalToken() {
        String token = properties.getQoderwork().getAccessToken();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token.trim());
    }

    private Set<String> allowedHosts() {
        return new LinkedHashSet<>(properties.getQoderwork().getAllowedHosts());
    }

    private static Optional<String> normalizeShareId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        if (!SHARE_ID_PATTERN.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static boolean isQoderWebsiteHost(String host) {
        return host.equals("qoder.com")
                || host.endsWith(".qoder.com")
                || host.equals("qoder.com.cn")
                || host.endsWith(".qoder.com.cn")
                || host.equals("qoder.sh")
                || host.endsWith(".qoder.sh");
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String encodeShareId(String shareId) {
        return java.net.URLEncoder.encode(shareId, java.nio.charset.StandardCharsets.UTF_8);
    }
}
