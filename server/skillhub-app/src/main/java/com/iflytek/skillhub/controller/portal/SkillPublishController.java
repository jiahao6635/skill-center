package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Upload endpoints for skill packages.
 *
 * <p>The controller is responsible for archive extraction and request shaping,
 * while the domain service owns all publication validation and state changes.
 */
@RestController
@RequestMapping({"/api/v1/skills", "/api/web/skills"})
public class SkillPublishController extends BaseApiController {

    private final SkillPublishService skillPublishService;
    private final SkillPackageArchiveExtractor skillPackageArchiveExtractor;
    private final SkillHubMetrics skillHubMetrics;

    public SkillPublishController(SkillPublishService skillPublishService,
                                  SkillPackageArchiveExtractor skillPackageArchiveExtractor,
                                  ApiResponseFactory responseFactory,
                                  SkillHubMetrics skillHubMetrics) {
        super(responseFactory);
        this.skillPublishService = skillPublishService;
        this.skillPackageArchiveExtractor = skillPackageArchiveExtractor;
        this.skillHubMetrics = skillHubMetrics;
    }

    /**
     * Publishes an uploaded package into the target namespace after archive
     * extraction and visibility parsing.
     */
    @PostMapping("/{namespace}/publish")
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    public ApiResponse<PublishResponse> publish(
            @PathVariable String namespace,
            @RequestParam("file") MultipartFile file,
            @RequestParam("visibility") String visibility,
            @RequestParam(value = "confirmWarnings", defaultValue = "false") boolean confirmWarnings,
            @AuthenticationPrincipal PlatformPrincipal principal) throws IOException {

        SkillVisibility skillVisibility = SkillVisibility.valueOf(visibility.toUpperCase());

        SkillPublishService.PublishResult publishResult = skillPublishService.publishFromEntries(
                namespace,
                extractEntries(file, confirmWarnings),
                principal.userId(),
                skillVisibility,
                principal.platformRoles(),
                confirmWarnings
        );

        PublishResponse response = toResponse(publishResult);
        skillHubMetrics.incrementSkillPublish(namespace, publishResult.version().getStatus().name());

        return ok("response.success.published", response);
    }

    @PostMapping("/by-id/{skillId}/private-versions")
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    @Operation(tags = "skill-private-version-controller")
    public ApiResponse<PublishResponse> save(@PathVariable Long skillId, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "confirmWarnings", defaultValue = "false") boolean confirmWarnings,
            @AuthenticationPrincipal PlatformPrincipal principal) throws IOException {
        var result = skillPublishService.savePrivateVersion(skillId, extractEntries(file, confirmWarnings),
                principal.userId(), principal.platformRoles(), confirmWarnings);
        return ok("response.success.updated", toResponse(result));
    }

    private List<PackageEntry> extractEntries(MultipartFile file, boolean confirmWarnings) throws IOException {
        SkillPackageArchiveExtractor.ExtractionResult extracted;
        try {
            extracted = skillPackageArchiveExtractor.extractWithWarnings(file);
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.publish.package.invalid", ex.getMessage());
        }
        if (!confirmWarnings && !extracted.warnings().isEmpty()) {
            throw new DomainBadRequestException("error.skill.publish.precheck.confirmRequired", String.join("\n", extracted.warnings()));
        }
        return extracted.entries();
    }

    private PublishResponse toResponse(SkillPublishService.PublishResult publishResult) {
        return new PublishResponse(
                publishResult.skillId(),
                publishResult.namespace(),
                publishResult.slug(),
                publishResult.version().getVersion(),
                publishResult.version().getStatus().name(),
                publishResult.version().getFileCount(),
                publishResult.version().getTotalSize()
        );
    }
}
