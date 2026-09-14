package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.dto.SkillUsageStats.*;
import com.iflytek.skillhub.service.SkillUsageStatsAppService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/skill-invocations")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SkillUsageStatsController extends BaseApiController {
    private final SkillUsageStatsAppService service;
    private final ApiResponseFactory responses;
    public SkillUsageStatsController(ApiResponseFactory responses, SkillUsageStatsAppService service) {
        super(responses); this.service = service; this.responses = responses;
    }
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidParameters() { return responses.error(400, "error.badRequest"); }
    @GetMapping("/summary")
    public ApiResponse<Summary> summary(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String email, @RequestParam(required = false) String product) {
        return ok("response.success.read", service.summary(from, to, email, product));
    }
    @GetMapping("/skills")
    public ApiResponse<PageResponse<SkillRank>> skills(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String email, @RequestParam(required = false) String product,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", service.skills(from, to, email, product, search, page, size));
    }
    @GetMapping("/users")
    public ApiResponse<PageResponse<UserRank>> users(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String email, @RequestParam(required = false) String product,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", service.users(from, to, email, product, page, size));
    }
    @GetMapping("/user-options")
    public ApiResponse<List<UserOption>> userOptions(@RequestParam(defaultValue = "") String search) {
        return ok("response.success.read", service.userOptions(search));
    }
}
