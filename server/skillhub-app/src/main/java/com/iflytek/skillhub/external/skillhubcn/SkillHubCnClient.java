package com.iflytek.skillhub.external.skillhubcn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.ExternalSkillHubProperties;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.external.ExternalSkillDetail;
import com.iflytek.skillhub.dto.external.ExternalSkillFile;
import com.iflytek.skillhub.dto.external.ExternalSkillSearchResponse;
import com.iflytek.skillhub.dto.external.ExternalSkillSummary;
import com.iflytek.skillhub.dto.external.ExternalSkillVersion;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.Duration;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.JavaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SkillHubCnClient {
    public static final String PROVIDER = "skillhub-cn";

    private final ExternalSkillHubProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final long maxPackageSize;
    private final StringRedisTemplate redisTemplate;
    private static final Duration SEARCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration DETAIL_CACHE_TTL = Duration.ofMinutes(10);

    public SkillHubCnClient(ExternalSkillHubProperties properties, ObjectMapper objectMapper,
                            SkillPublishProperties publishProperties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.maxPackageSize = publishProperties.getMaxPackageSize();
        this.redisTemplate = redisTemplate;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public ExternalSkillSearchResponse search(String keyword, String category, String sort, int page, int size) {
        String key = cacheKey("search", keyword, category, sort, Integer.toString(page), Integer.toString(size));
        return cached(key, SEARCH_CACHE_TTL, objectMapper.constructType(ExternalSkillSearchResponse.class),
                () -> searchFresh(keyword, category, sort, page, size));
    }

    public ExternalSkillSearchResponse searchFresh(String keyword, String category, String sort, int page, int size) {
        requireEnabled();
        int externalPage = Math.max(0, page) + 1;
        String sortBy = switch (sort == null ? "relevance" : sort) {
            case "downloads" -> "downloads";
            case "newest" -> "updated_at";
            default -> "score";
        };
        String path = "/api/skills?page=" + externalPage + "&pageSize=" + Math.max(1, Math.min(size, 50))
                + param("keyword", keyword) + param("category", category)
                + "&sortBy=" + sortBy + "&order=desc";
        JsonNode data = getJson(path).path("data");
        List<ExternalSkillSummary> items = new ArrayList<>();
        data.path("skills").forEach(node -> items.add(toSummary(node)));
        return new ExternalSkillSearchResponse(items, data.path("total").asLong(), Math.max(0, page), size);
    }

    public JsonNode categories() {
        return cached(cacheKey("categories"), SEARCH_CACHE_TTL, objectMapper.constructType(JsonNode.class),
                this::categoriesFresh);
    }

    public JsonNode categoriesFresh() {
        requireEnabled();
        return getJson("/api/v1/categories").path("items");
    }

    public ExternalSkillDetail detail(String slug) {
        return cached(cacheKey("detail", slug), DETAIL_CACHE_TTL,
                objectMapper.constructType(ExternalSkillDetail.class), () -> detailFresh(slug));
    }

    public ExternalSkillDetail detailFresh(String slug) {
        requireCoordinate(slug);
        JsonNode root = getJson("/api/v1/skills/" + encode(slug));
        JsonNode skill = root.path("skill");
        JsonNode owner = root.path("owner");
        JsonNode latest = root.path("latestVersion");
        String sourceUrl = text(skill, "sourceUrl");
        if (sourceUrl.isBlank()) sourceUrl = "https://skillhub.cn/skills/" + encode(slug);
        String ownerHandle = text(owner, "handle");
        if (ownerHandle.isBlank()) ownerHandle = text(skill, "githubAuthorLogin");
        if (ownerHandle.isBlank()) ownerHandle = text(skill, "upstream_owner_login");
        ExternalSkillSummary summary = new ExternalSkillSummary(
                PROVIDER, text(skill, "slug"), text(skill, "displayName"), text(skill, "summary"),
                text(skill, "summary_zh"), ownerHandle, text(skill.path("tags"), "latest"),
                text(skill, "category"), text(skill, "iconUrl"), sourceUrl,
                skill.path("stats").path("downloads").asLong(), skill.path("stats").path("installs").asLong(),
                skill.path("stats").path("stars").asLong(), skill.path("verified").asBoolean());
        return new ExternalSkillDetail(summary, new ExternalSkillVersion(
                text(latest, "version"), text(latest, "changelog"), nullableLong(latest, "createdAt"),
                root.path("securityReports")), root.path("securityReports"));
    }

    public List<ExternalSkillVersion> versions(String slug) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, ExternalSkillVersion.class);
        return cached(cacheKey("versions", slug), DETAIL_CACHE_TTL, type, () -> versionsFresh(slug));
    }

    public List<ExternalSkillVersion> versionsFresh(String slug) {
        requireCoordinate(slug);
        List<ExternalSkillVersion> versions = new ArrayList<>();
        getJson("/api/v1/skills/" + encode(slug) + "/versions").path("versions").forEach(node ->
                versions.add(new ExternalSkillVersion(text(node, "version"), text(node, "changelog"),
                        nullableLong(node, "createdAt"), node.path("securityReports"))));
        return versions;
    }

    public List<ExternalSkillFile> files(String slug, String version) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, ExternalSkillFile.class);
        return cached(cacheKey("files", slug, version), DETAIL_CACHE_TTL, type, () -> filesFresh(slug, version));
    }

    public List<ExternalSkillFile> filesFresh(String slug, String version) {
        requireCoordinate(slug);
        requireCoordinate(version);
        List<ExternalSkillFile> files = new ArrayList<>();
        getJson("/api/v1/skills/" + encode(slug) + "/files?version=" + encode(version)).path("files").forEach(node ->
                files.add(new ExternalSkillFile(text(node, "path"), node.path("size").asLong(), text(node, "sha256"))));
        return files;
    }

    public String declaredLicenseFresh(String slug) {
        requireCoordinate(slug);
        JsonNode root = getJson("/api/v1/skills/" + encode(slug));
        for (JsonNode candidate : List.of(
                root.path("license"),
                root.path("licenseExpression"),
                root.path("skill").path("license"),
                root.path("latestVersion").path("license"))) {
            if (candidate.isTextual() && !candidate.asText().isBlank()) return candidate.asText().trim();
        }
        return null;
    }

    public String file(String slug, String version, String path) {
        requireCoordinate(slug);
        requireCoordinate(version);
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
            throw new DomainBadRequestException("error.externalSkill.filePath.invalid");
        }
        URI redirect = requestRedirect("/api/v1/skills/" + encode(slug) + "/file?path=" + encode(path)
                + "&version=" + encode(version));
        byte[] body = downloadAllowed(redirect, 10 * 1024 * 1024);
        return new String(body, StandardCharsets.UTF_8);
    }

    public byte[] download(String slug, String version) {
        requireCoordinate(slug);
        requireCoordinate(version);
        URI redirect = requestRedirect("/api/v1/download?slug=" + encode(slug) + "&version=" + encode(version));
        return downloadAllowed(redirect, maxPackageSize);
    }

    private JsonNode getJson(String path) {
        try {
            URI uri = properties.getApiBaseUrl().resolve(path);
            validateUri(uri, properties.getApiBaseUrl().getHost());
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(uri)
                    .timeout(properties.getRequestTimeout()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw upstream(response.statusCode());
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        }
    }

    private URI requestRedirect(String path) {
        try {
            URI uri = properties.getApiBaseUrl().resolve(path);
            validateUri(uri, properties.getApiBaseUrl().getHost());
            HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(uri)
                    .timeout(properties.getRequestTimeout()).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 301 && response.statusCode() != 302 && response.statusCode() != 303
                    && response.statusCode() != 307 && response.statusCode() != 308) {
                throw upstream(response.statusCode());
            }
            URI location = response.headers().firstValue("location").map(uri::resolve)
                    .orElseThrow(() -> upstream(response.statusCode()));
            validateUri(location, null);
            String host = location.getHost().toLowerCase(Locale.ROOT);
            if (properties.getAllowedDownloadHosts().stream().map(value -> value.toLowerCase(Locale.ROOT))
                    .noneMatch(host::equals)) {
                throw new DomainBadRequestException("error.externalSkill.redirect.notAllowed");
            }
            return location;
        } catch (IOException e) {
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        }
    }

    private byte[] downloadAllowed(URI uri, long limit) {
        try {
            HttpResponse<InputStream> response = httpClient.send(HttpRequest.newBuilder(uri)
                    .timeout(properties.getRequestTimeout()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw upstream(response.statusCode());
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (contentLength > limit) throw new DomainBadRequestException("error.externalSkill.package.tooLarge");
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) throw new DomainBadRequestException("error.externalSkill.package.tooLarge");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        }
    }

    private void validateUri(URI uri, String requiredHost) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null
                || (requiredHost != null && !requiredHost.equalsIgnoreCase(uri.getHost()))) {
            throw new DomainBadRequestException("error.externalSkill.url.notAllowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new DomainBadRequestException("error.externalSkill.url.notAllowed");
                }
            }
        } catch (IOException e) {
            throw new DomainBadRequestException("error.externalSkill.upstream.unavailable");
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new DomainBadRequestException("error.externalSkill.provider.disabled");
    }

    private void requireCoordinate(String value) {
        requireEnabled();
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,255}"))
            throw new DomainBadRequestException("error.externalSkill.coordinate.invalid");
    }

    private ExternalSkillSummary toSummary(JsonNode node) {
        return new ExternalSkillSummary(PROVIDER, text(node, "slug"), text(node, "name"),
                text(node, "description"), text(node, "description_zh"), text(node, "ownerName"),
                text(node, "version"), text(node, "category"), text(node, "iconUrl"),
                text(node, "upstream_url"), node.path("downloads").asLong(), node.path("installs").asLong(),
                node.path("stars").asLong(), node.path("verified").asBoolean());
    }

    private DomainBadRequestException upstream(int status) {
        return new DomainBadRequestException("error.externalSkill.upstream.status", status);
    }

    private String param(String name, String value) { return value == null || value.isBlank() ? "" : "&" + name + "=" + encode(value); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private Long nullableLong(JsonNode node, String field) { return node.path(field).isNumber() ? node.path(field).asLong() : null; }

    private <T> T cached(String key, Duration ttl, JavaType type, Supplier<T> loader) {
        requireEnabled();
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return objectMapper.readValue(cached, type);
        } catch (Exception ignored) {
            // Browse caching is best-effort; upstream remains the source of truth.
        }
        T value = loader.get();
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // Redis outages must not make the external catalog unavailable.
        }
        return value;
    }

    private String cacheKey(String operation, String... values) {
        String joined = String.join("\u0000", values == null ? new String[0]
                : java.util.Arrays.stream(values).map(value -> value == null ? "" : value).toArray(String[]::new));
        try {
            String digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(joined.getBytes(StandardCharsets.UTF_8)));
            return "external-skill:" + PROVIDER + ":" + operation + ":" + digest;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
