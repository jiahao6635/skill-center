package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import com.iflytek.skillhub.domain.usage.SkillUsageActorKind;
import com.iflytek.skillhub.domain.usage.SkillUsageAuthMethod;
import com.iflytek.skillhub.domain.usage.SkillUsageClient;
import com.iflytek.skillhub.domain.usage.SkillUsageRequestContext;
import com.iflytek.skillhub.domain.usage.UsageAttribution;
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
                                         Map<Long, NamespaceRole> userNsRoles,
                                         UsageAttribution attribution) {
        SkillDownloadService.PresignedDownload presigned = skillDownloadService.presignDownload(
                namespaceSlug, skillSlug, version, userId, userNsRoles);

        Instant expiresAt = Instant.now().plus(DownloadLinkStore.TTL_MINUTES, ChronoUnit.MINUTES);

        if (presigned.presignedUrl() != null && !presigned.presignedUrl().isBlank()) {
            String token = UUID.randomUUID().toString();
            SkillUsageRequestContext issuerContext = attribution == null ? null : attribution.requestContext();
            downloadLinkStore.save(token, new DownloadLinkStore.DownloadLinkData(
                    presigned.presignedUrl(), presigned.skillId(), presigned.versionId(),
                    presigned.filename(), presigned.published(),
                    attribution == null ? null : attribution.actorUserId(),
                    attribution == null ? null : attribution.actorKey(),
                    attribution == null || attribution.actorKind() == null
                            ? null : attribution.actorKind().name(),
                    issuerContext == null || issuerContext.client() == null
                            ? null : issuerContext.client().name(),
                    issuerContext == null || issuerContext.authMethod() == null
                            ? null : issuerContext.authMethod().name()));
            return IssueResult.redirect(token, expiresAt);
        }

        // Local storage cannot presign: fall back to the public CLI download
        // endpoint. No token, no issuer — the later CLI GET is an ordinary
        // path-B download attributed to the fetcher, NOT a deeplink.
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
    public String resolveForRedirect(String token, UsageAttribution fetcherAttribution) {
        DownloadLinkStore.DownloadLinkData data = downloadLinkStore.get(token);
        if (data == null) {
            throw new DomainNotFoundException("error.downloadLink.notFound");
        }
        if (data.isPublished() && downloadLinkStore.markCountedIfAbsent(token)) {
            UsageAttribution issuer = attributionFromIssuer(data, fetcherAttribution);
            skillDownloadService.recordDownloadById(data.getSkillId(), data.getVersionId(),
                    null, null, null, "deeplink", issuer);
        }
        return data.getPresignedUrl();
    }

    /**
     * Deep-link downloads are attributed to the ISSUER (who created the link),
     * never to the fetching client's IP/UA-derived identity — except that the
     * request context keeps the fetcher's requestId/ip/ua for admin triage.
     * Tokens minted before the issuer fields existed fall back to an anonymous
     * WEB attribution keyed by the fetcher's anonymous fingerprint.
     */
    private UsageAttribution attributionFromIssuer(DownloadLinkStore.DownloadLinkData data,
                                                   UsageAttribution fetcherAttribution) {
        SkillUsageRequestContext fetcherContext = fetcherAttribution == null
                ? null : fetcherAttribution.requestContext();
        boolean hasIssuer = data.getIssuerActorKey() != null && !data.getIssuerActorKey().isBlank();
        SkillUsageClient client = hasIssuer
                ? parseClient(data.getIssuerClient())
                : SkillUsageClient.WEB;
        SkillUsageAuthMethod authMethod = hasIssuer
                ? parseAuthMethod(data.getIssuerAuthMethod())
                : SkillUsageAuthMethod.ANONYMOUS;
        SkillUsageRequestContext context = new SkillUsageRequestContext(
                client,
                authMethod,
                fetcherContext == null ? null : fetcherContext.requestId(),
                fetcherContext == null ? null : fetcherContext.clientIp(),
                fetcherContext == null ? null : fetcherContext.userAgent());
        if (hasIssuer) {
            return new UsageAttribution(
                    data.getIssuerUserId(),
                    data.getIssuerActorKey(),
                    parseActorKind(data.getIssuerActorKind()),
                    context);
        }
        String fetcherActorKey = fetcherAttribution == null ? null : fetcherAttribution.actorKey();
        return new UsageAttribution(null, fetcherActorKey, SkillUsageActorKind.ANONYMOUS, context);
    }

    private static SkillUsageClient parseClient(String value) {
        try {
            return value == null ? SkillUsageClient.WEB : SkillUsageClient.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return SkillUsageClient.WEB;
        }
    }

    private static SkillUsageAuthMethod parseAuthMethod(String value) {
        try {
            return value == null ? SkillUsageAuthMethod.ANONYMOUS : SkillUsageAuthMethod.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return SkillUsageAuthMethod.ANONYMOUS;
        }
    }

    private static SkillUsageActorKind parseActorKind(String value) {
        try {
            return value == null ? SkillUsageActorKind.ANONYMOUS : SkillUsageActorKind.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return SkillUsageActorKind.ANONYMOUS;
        }
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
