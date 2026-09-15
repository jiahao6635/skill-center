package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.SkillSharingAppService;
import java.io.IOException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/v1/skills/by-id/{skillId}", "/api/web/skills/by-id/{skillId}"})
public class SkillPrivateVersionController extends BaseApiController {
    private final SkillSharingAppService appService;
    private final SkillPackageArchiveExtractor extractor;
    public SkillPrivateVersionController(SkillSharingAppService appService, SkillPackageArchiveExtractor extractor,
                                         ApiResponseFactory responses) {
        super(responses); this.appService = appService; this.extractor = extractor;
    }
    @PostMapping("/private-versions")
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    public ApiResponse<PublishResponse> save(@PathVariable Long skillId, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "confirmWarnings", defaultValue = "false") boolean confirmWarnings,
            @AuthenticationPrincipal PlatformPrincipal principal) throws IOException {
        SkillPackageArchiveExtractor.ExtractionResult extracted;
        try {
            extracted = extractor.extractWithWarnings(file);
        } catch (IllegalArgumentException ex) {
            throw new DomainBadRequestException("error.skill.publish.package.invalid", ex.getMessage());
        }
        if (!confirmWarnings && !extracted.warnings().isEmpty()) {
            throw new DomainBadRequestException("error.skill.publish.precheck.confirmRequired", String.join("\n", extracted.warnings()));
        }
        return ok("response.success.updated", appService.savePrivateVersion(skillId, extracted.entries(), principal.userId(),
                principal.platformRoles(), confirmWarnings));
    }
}
