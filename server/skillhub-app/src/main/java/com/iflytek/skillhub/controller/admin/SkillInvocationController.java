package com.iflytek.skillhub.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.*;
import com.iflytek.skillhub.service.*;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;

/** Transport only. Internal ingestion has its own stateless service-key filter chain. */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="SkillUsageKey",
        type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
        in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER, paramName="X-Skill-Usage-Key")
public class SkillInvocationController extends BaseApiController {
    private final ApiResponseFactory responses;
    private final SkillInvocationAppService ingest;
    private final SkillInvocationQueryAppService query;
    public SkillInvocationController(SkillInvocationAppService ingest,SkillInvocationQueryAppService query,ApiResponseFactory factory) {
        super(factory); this.responses=factory; this.ingest=ingest; this.query=query;
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="recordSkillInvocations",
            security=@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="SkillUsageKey"))
    @PostMapping("/api/internal/v1/skill-invocations/batch")
    public ApiResponse<SkillInvocationBatchResponse> ingest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(
                array=@ArraySchema(schema=@Schema(implementation=SkillInvocationInput.class))))
            @RequestBody List<JsonNode> events) {
        if (events.isEmpty() || events.size()>500) throw new IllegalArgumentException("Batch must contain 1 to 500 events");
        return ok("response.success.read",ingest.ingest(events));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidBody() { return responses.error(400, "error.badRequest"); }

    // Keep infrastructure failures on this authenticated endpoint. The global
    // IllegalStateException handler rethrows non-session failures into /error,
    // whose browser security chain can incorrectly turn a committed retry into 401.
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> storageUnavailable() { return responses.error(500, "error.internal"); }

    @io.swagger.v3.oas.annotations.Operation(operationId="listSkillInvocations")
    @GetMapping("/api/v1/admin/skill-invocations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<PageResponse<SkillInvocationItem>> list(
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,
            @RequestParam(required=false) String email,@RequestParam(required=false) String userId,
            @RequestParam(required=false) String skillName,@RequestParam(required=false) Long skillId,
            @RequestParam(required=false) String product,@RequestParam(required=false) String sessionId,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) {
        return ok("response.success.read",query.list(page,size,email,userId,skillName,skillId,product,sessionId,from,to));
    }
}
