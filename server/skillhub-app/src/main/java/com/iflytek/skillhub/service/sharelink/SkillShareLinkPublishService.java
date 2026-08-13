package com.iflytek.skillhub.service.sharelink;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.PublishFromUrlRequest;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates "paste share link → download zip → publish" for supported providers.
 */
@Service
public class SkillShareLinkPublishService {

    private static final Logger log = LoggerFactory.getLogger(SkillShareLinkPublishService.class);

    private final QoderWorkShareLinkResolver qoderWorkShareLinkResolver;
    private final SkillPackageArchiveExtractor archiveExtractor;
    private final SkillPublishService skillPublishService;
    private final SkillHubMetrics skillHubMetrics;

    public SkillShareLinkPublishService(
            QoderWorkShareLinkResolver qoderWorkShareLinkResolver,
            SkillPackageArchiveExtractor archiveExtractor,
            SkillPublishService skillPublishService,
            SkillHubMetrics skillHubMetrics) {
        this.qoderWorkShareLinkResolver = qoderWorkShareLinkResolver;
        this.archiveExtractor = archiveExtractor;
        this.skillPublishService = skillPublishService;
        this.skillHubMetrics = skillHubMetrics;
    }

    public PublishResponse publish(String namespace, PublishFromUrlRequest request, PlatformPrincipal principal) {
        URI shareUri = parseUri(request.url());
        SkillVisibility visibility = parseVisibility(request.visibility());
        boolean confirmWarnings = request.confirmWarningsOrDefault();

        byte[] zipBytes = downloadPackage(shareUri);
        List<PackageEntry> entries;
        List<String> extractionWarnings;
        try {
            SkillPackageArchiveExtractor.ExtractionResult extractionResult =
                    archiveExtractor.extractWithWarnings(zipBytes);
            entries = extractionResult.entries();
            extractionWarnings = extractionResult.warnings();
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.publish.package.invalid", ex.getMessage());
        } catch (IOException ex) {
            throw new DomainBadRequestException("error.skill.publish.package.invalid", ex.getMessage());
        }

        if (!confirmWarnings && !extractionWarnings.isEmpty()) {
            throw new DomainBadRequestException(
                    "error.skill.publish.precheck.confirmRequired",
                    String.join("\n", extractionWarnings));
        }

        SkillPublishService.PublishResult publishResult = skillPublishService.publishFromEntries(
                namespace,
                entries,
                principal.userId(),
                visibility,
                principal.platformRoles(),
                confirmWarnings
        );

        skillHubMetrics.incrementSkillPublish(namespace, publishResult.version().getStatus().name());
        log.info("Published skill from share link into {} as {}@{}",
                namespace, publishResult.slug(), publishResult.version().getVersion());

        return new PublishResponse(
                publishResult.skillId(),
                namespace,
                publishResult.slug(),
                publishResult.version().getVersion(),
                publishResult.version().getStatus().name(),
                publishResult.version().getFileCount(),
                publishResult.version().getTotalSize()
        );
    }

    private byte[] downloadPackage(URI shareUri) {
        if (qoderWorkShareLinkResolver.supports(shareUri)) {
            return qoderWorkShareLinkResolver.downloadPackage(shareUri);
        }
        throw new DomainBadRequestException("error.skill.publish.shareLink.unsupported");
    }

    private static URI parseUri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.invalidUrl");
        }
        try {
            URI uri = URI.create(raw.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new DomainBadRequestException("error.skill.publish.shareLink.invalidUrl");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.invalidUrl");
        }
    }

    private static SkillVisibility parseVisibility(String visibility) {
        try {
            return SkillVisibility.valueOf(visibility.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new DomainBadRequestException("error.skill.publish.shareLink.invalidVisibility");
        }
    }
}
