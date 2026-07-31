package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the deep-link download flow.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #issueDownloadLink} — called by the authenticated web endpoint
 *       when a user clicks "Open in QoderWork". It presigns the package URL,
 *       stores it behind an unguessable token, and returns either the token
 *       (redirect mode) or a fallback download path (dev/local storage).</li>
 *   <li>{@link #resolveForRedirect} — called by the public redirect endpoint
 *       when QoderWork fetches the package. It records the download metric and
 *       returns the presigned URL to redirect to.</li>
 * </ul>
 */
@Service
public class SkillDownloadLinkService {

    private final SkillDownloadService skillDownloadService;
    private final DownloadLinkStore downloadLinkStore;

    public SkillDownloadLinkService(SkillDownloadService skillDownloadService,
                                    DownloadLinkStore downloadLinkStore) {
        this.skillDownloadService = skillDownloadService;
        this.downloadLinkStore = downloadLinkStore;
    }

    /**
     * Issues a short-lived download link for the given skill.
     *
     * <p>When the storage backend supports presigned URLs, the link is a token
     * that the public redirect endpoint resolves. Otherwise (local storage in
     * dev) it falls back to the streamed CLI download path.
     */
    public IssueResult issueDownloadLink(String namespaceSlug,
                                         String skillSlug,
                                         String version,
                                         String userId,
                                         Map<Long, NamespaceRole> userNsRoles) {
        SkillDownloadService.PresignedDownload presigned = skillDownloadService.presignDownload(
                namespaceSlug, skillSlug, version, userId, userNsRoles);

        Instant expiresAt = Instant.now().plus(DownloadLinkStore.TTL_MINUTES, ChronoUnit.MINUTES);

        if (presigned.presignedUrl() != null && !presigned.presignedUrl().isBlank()) {
            String token = UUID.randomUUID().toString();
            downloadLinkStore.save(token, new DownloadLinkStore.DownloadLinkData(
                    presigned.presignedUrl(), presigned.skillId(), presigned.versionId(),
                    presigned.filename(), presigned.published()));
            return IssueResult.redirect(token, expiresAt);
        }

        // Local storage cannot presign: fall back to the public CLI download endpoint.
        String fallbackPath = "/api/cli/v1/skills/" + namespaceSlug + "/" + skillSlug + "/download";
        return IssueResult.fallback(fallbackPath, expiresAt);
    }

    /**
     * Resolves a download token into the presigned URL to redirect to,
     * recording the download metric.
     *
     * <p>Counting is guarded twice: only PUBLISHED versions contribute to
     * metrics (mirroring the streamed download path), and a per-token SETNX
     * marker makes the count idempotent so client retries never double-count.
     * The redirect itself stays repeatable for robustness.
     *
     * @throws DomainNotFoundException if the token is unknown or expired (404)
     */
    public String resolveForRedirect(String token) {
        DownloadLinkStore.DownloadLinkData data = downloadLinkStore.get(token);
        if (data == null) {
            throw new DomainNotFoundException("error.downloadLink.notFound");
        }
        if (data.isPublished() && downloadLinkStore.markCountedIfAbsent(token)) {
            skillDownloadService.recordDownloadById(data.getSkillId(), data.getVersionId());
        }
        return data.getPresignedUrl();
    }

    /**
     * Result of issuing a download link: either a redirect {@code token}
     * (production / presign-capable storage) or a {@code fallbackPath}
     * (local storage). Exactly one of the two is non-null.
     */
    public record IssueResult(String token, String fallbackPath, Instant expiresAt) {
        public static IssueResult redirect(String token, Instant expiresAt) {
            return new IssueResult(token, null, expiresAt);
        }

        public static IssueResult fallback(String fallbackPath, Instant expiresAt) {
            return new IssueResult(null, fallbackPath, expiresAt);
        }

        public boolean isRedirect() {
            return token != null;
        }
    }
}
