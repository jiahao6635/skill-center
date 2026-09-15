package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.repository.SkillSharingQueryRepository;
import com.iflytek.skillhub.service.SkillSharingAppService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Stable-ID entry points for sharing an existing skill and resolving its current location. */
@RestController
@RequestMapping({"/api/v1/skills/by-id/{skillId}", "/api/web/skills/by-id/{skillId}"})
public class SkillSharingController extends BaseApiController {
    private final SkillSharingAppService appService;
    private final SkillSharingQueryRepository queries;
    public SkillSharingController(SkillSharingAppService appService, SkillSharingQueryRepository queries, ApiResponseFactory responses) {
        super(responses); this.appService = appService; this.queries = queries;
    }
    @GetMapping("/sharing")
    public ApiResponse<SkillSharingSettingsResponse> settings(@PathVariable Long skillId, @RequestAttribute("userId") String userId) {
        return ok("response.success.read", queries.settings(skillId, userId));
    }
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    @PostMapping("/sharing/precheck")
    public ApiResponse<SkillSharePrecheckResponse> precheck(@PathVariable Long skillId, @Valid @RequestBody SkillShareCommand command,
                                                          @RequestAttribute("userId") String userId) {
        return ok("response.success.read", appService.precheck(skillId, command, userId));
    }
    @RateLimit(category = "publish", authenticated = 10, anonymous = 0)
    @PostMapping("/sharing")
    public ApiResponse<SkillShareResponse> submit(@PathVariable Long skillId, @Valid @RequestBody SkillShareCommand command,
                                                @RequestAttribute("userId") String userId) {
        return ok("response.success.read", appService.submit(skillId, command, userId));
    }
    @PostMapping("/sharing/{requestId}/withdraw")
    public ApiResponse<SkillShareResponse> withdraw(@PathVariable Long skillId, @PathVariable Long requestId,
                                                   @RequestAttribute("userId") String userId) {
        return ok("response.success.read", appService.withdraw(skillId, requestId, userId));
    }
    @GetMapping("/location")
    public ApiResponse<SkillLocationResponse> location(@PathVariable Long skillId,
            @RequestAttribute(value = "userId", required = false) String userId,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> roles) {
        return ok("response.success.read", appService.location(skillId, userId, roles));
    }
}
