package com.iflytek.skillhub.service.cli;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.service.SkillQueryService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.SkillSummaryResponse;
import com.iflytek.skillhub.dto.cli.CliDryRunResponse;
import com.iflytek.skillhub.dto.cli.CliPublishResponse;
import com.iflytek.skillhub.dto.cli.CliResolveResponse;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.iflytek.skillhub.service.SkillSearchAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CliSkillAppService {

    private final SkillSearchAppService skillSearchAppService;
    private final SkillQueryService skillQueryService;
    private final SkillDownloadService skillDownloadService;
    private final SkillPublishService skillPublishService;

    public CliSkillAppService(
            SkillSearchAppService skillSearchAppService,
            SkillQueryService skillQueryService,
            SkillDownloadService skillDownloadService,
            SkillPublishService skillPublishService) {
        this.skillSearchAppService = skillSearchAppService;
        this.skillQueryService = skillQueryService;
        this.skillDownloadService = skillDownloadService;
        this.skillPublishService = skillPublishService;
    }

    public record CliSearchItem(String namespace, String slug, String latestVersion, String summary) {}
    public record CliSearchResult(List<CliSearchItem> items, long total, int limit) {}

    public CliSearchResult search(String q, int limit, String userId, Map<Long, NamespaceRole> userNsRoles) {
        SkillSearchAppService.SearchResponse response = skillSearchAppService.searchInstallableLatest(
                q, null, "newest", 0, limit, userId, userNsRoles
        );

        List<CliSearchItem> items = response.items().stream()
                .map(item -> new CliSearchItem(
                        item.namespace(),
                        item.slug(),
                        item.publishedVersion().version(),
                        item.summary()
                ))
                .toList();

        return new CliSearchResult(items, response.total(), limit);
    }

    public CliResolveResponse resolve(String namespace, String slug, String version, String userId, Map<Long, NamespaceRole> userNsRoles) {
        SkillQueryService.ResolvedVersionDTO resolved = skillQueryService.resolveVersion(
                namespace, slug, version, null, null, userId, userNsRoles
        );

        return new CliResolveResponse(
                resolved.namespace(),
                resolved.slug(),
                resolved.version(),
                resolved.versionId(),
                resolved.fingerprint(),
                resolved.downloadUrl()
        );
    }

    public ResponseEntity<InputStreamResource> downloadLatest(String namespace, String slug, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        @SuppressWarnings("unchecked")
        Map<Long, NamespaceRole> userNsRoles = (Map<Long, NamespaceRole>) request.getAttribute("userNsRoles");

        SkillDownloadService.DownloadResult result = skillDownloadService.downloadLatest(
                namespace, slug, userId, userNsRoles != null ? userNsRoles : Map.of()
        );

        return buildDownloadResponse(result);
    }

    public ResponseEntity<InputStreamResource> downloadVersion(String namespace, String slug, String version, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        @SuppressWarnings("unchecked")
        Map<Long, NamespaceRole> userNsRoles = (Map<Long, NamespaceRole>) request.getAttribute("userNsRoles");

        SkillDownloadService.DownloadResult result = skillDownloadService.downloadVersion(
                namespace, slug, version, userId, userNsRoles != null ? userNsRoles : Map.of()
        );

        return buildDownloadResponse(result);
    }

    public CliDryRunResponse validatePublish(String namespace, List<PackageEntry> entries, String publisherId, SkillVisibility visibility, Set<String> platformRoles) {
        SkillPublishService.DryRunResult result = skillPublishService.validateOnly(
                namespace, entries, publisherId, visibility, platformRoles);
        String fingerprint = packageFingerprint(entries);
        String warningDigest = sha256(fingerprint + "\n" + String.join("\n", result.warnings().stream().sorted().toList()));
        return new CliDryRunResponse(
                result.valid(),
                result.errors(),
                result.warnings(),
                result.resolvedSlug(),
                result.resolvedVersion(),
                fingerprint,
                warningDigest,
                !result.warnings().isEmpty()
        );
    }

    public CliPublishResponse publish(String namespace, List<PackageEntry> entries, String publisherId, SkillVisibility visibility, Set<String> platformRoles) {
        return publish(namespace, entries, publisherId, visibility, platformRoles, false);
    }

    public CliPublishResponse publish(String namespace, List<PackageEntry> entries, String publisherId,
                                      SkillVisibility visibility, Set<String> platformRoles,
                                      boolean confirmWarnings) {
        SkillPublishService.PublishResult result = skillPublishService.publishFromEntries(
                namespace, entries, publisherId, visibility, platformRoles, confirmWarnings
        );

        String status = result.version().getStatus().name();
        String nextAction = switch (status) {
            case "SCANNING" -> "WAIT_FOR_SCAN";
            case "PENDING_REVIEW" -> "WAIT_FOR_REVIEW";
            case "UPLOADED" -> "NONE";
            default -> "VIEW_SKILL";
        };
        return new CliPublishResponse(
                namespace,
                result.slug(),
                result.version().getVersion(),
                visibility.name(),
                result.version().getId(),
                status,
                nextAction
        );
    }

    private String packageFingerprint(List<PackageEntry> entries) {
        String material = entries.stream()
                .sorted(java.util.Comparator.comparing(PackageEntry::path))
                .map(entry -> entry.path() + "\u0000" + sha256(entry.content()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return sha256(material);
    }

    private String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<InputStreamResource> buildDownloadResponse(SkillDownloadService.DownloadResult result) {
        if (result.presignedUrl() != null) {
            return ResponseEntity.status(302)
                    .header(HttpHeaders.LOCATION, result.presignedUrl())
                    .build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(MediaType.parseMediaType(result.contentType()))
                .contentLength(result.contentLength())
                .body(new InputStreamResource(result.openContent()));
    }
}
