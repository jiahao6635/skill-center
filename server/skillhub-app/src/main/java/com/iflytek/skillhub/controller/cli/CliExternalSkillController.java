package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.external.ExternalSkillDetail;
import com.iflytek.skillhub.dto.external.ExternalSkillImportRequest;
import com.iflytek.skillhub.dto.external.ExternalSkillImportResult;
import com.iflytek.skillhub.dto.external.ExternalSkillImportValidation;
import com.iflytek.skillhub.dto.external.ExternalSkillSearchResponse;
import com.iflytek.skillhub.external.skillhubcn.SkillHubCnClient;
import com.iflytek.skillhub.security.RequestAuthorizationContext;
import com.iflytek.skillhub.security.TokenGrantGuard;
import com.iflytek.skillhub.service.ExternalSkillImportAppService;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cli/v1/external")
public class CliExternalSkillController extends BaseApiController {
    private final SkillHubCnClient client;
    private final ExternalSkillImportAppService importService;
    private final NamespaceRepository namespaceRepository;
    private final TokenGrantGuard tokenGrantGuard;

    public CliExternalSkillController(ApiResponseFactory responseFactory, SkillHubCnClient client,
            ExternalSkillImportAppService importService, NamespaceRepository namespaceRepository, TokenGrantGuard tokenGrantGuard) {
        super(responseFactory); this.client = client; this.importService = importService;
        this.namespaceRepository = namespaceRepository; this.tokenGrantGuard = tokenGrantGuard;
    }

    @GetMapping("/search")
    public ApiResponse<ExternalSkillSearchResponse> search(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "relevance") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok("response.success.read", client.search(q, null, sort, page, size));
    }

    @GetMapping("/{slug}")
    public ApiResponse<ExternalSkillDetail> detail(@PathVariable String slug) { return ok("response.success.read", client.detail(slug)); }

    @PostMapping("/{slug}/versions/{version}/imports/validate")
    public ApiResponse<ExternalSkillImportValidation> validate(@PathVariable String slug, @PathVariable String version,
            @Valid @RequestBody ExternalSkillImportRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth) {
        guardNamespace(request.namespace(), auth);
        return ok("response.success.read", importService.validate(SkillHubCnClient.PROVIDER, slug, version, request, auth.userId()));
    }

    @PostMapping("/{slug}/versions/{version}/imports")
    public ApiResponse<ExternalSkillImportResult> importVersion(@PathVariable String slug, @PathVariable String version,
            @Valid @RequestBody ExternalSkillImportRequest request, @RequestAttribute("authorizationContext") RequestAuthorizationContext auth,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> nsRoles) {
        guardNamespace(request.namespace(), auth);
        return ok("response.success.created", importService.importVersion(SkillHubCnClient.PROVIDER, slug, version, request,
                auth.userId(), auth.platformRoles() != null ? auth.platformRoles() : Set.of(), nsRoles != null ? nsRoles : Map.of()));
    }

    private void guardNamespace(String slug, RequestAuthorizationContext auth) {
        String clean = slug != null && slug.startsWith("@") ? slug.substring(1) : slug;
        Long id = namespaceRepository.findBySlug(clean).orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", clean)).getId();
        tokenGrantGuard.requireAgent(auth, "skill:publish", id);
    }
}
