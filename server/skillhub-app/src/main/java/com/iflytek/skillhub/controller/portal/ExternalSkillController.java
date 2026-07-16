package com.iflytek.skillhub.controller.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.external.ExternalSkillDetail;
import com.iflytek.skillhub.dto.external.ExternalSkillFile;
import com.iflytek.skillhub.dto.external.ExternalSkillImportRequest;
import com.iflytek.skillhub.dto.external.ExternalSkillImportResult;
import com.iflytek.skillhub.dto.external.ExternalSkillImportValidation;
import com.iflytek.skillhub.dto.external.ExternalSkillProviderResponse;
import com.iflytek.skillhub.dto.external.ExternalSkillSearchResponse;
import com.iflytek.skillhub.dto.external.ExternalSkillVersion;
import com.iflytek.skillhub.external.skillhubcn.SkillHubCnClient;
import com.iflytek.skillhub.ratelimit.RateLimit;
import com.iflytek.skillhub.service.ExternalSkillImportAppService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web")
public class ExternalSkillController extends BaseApiController {
    private final SkillHubCnClient client;
    private final ExternalSkillImportAppService importService;

    public ExternalSkillController(ApiResponseFactory responseFactory, SkillHubCnClient client,
                                   ExternalSkillImportAppService importService) {
        super(responseFactory);
        this.client = client;
        this.importService = importService;
    }

    @GetMapping("/external-skill-providers")
    public ApiResponse<List<ExternalSkillProviderResponse>> providers() {
        return ok("response.success.read", List.of(new ExternalSkillProviderResponse(
                SkillHubCnClient.PROVIDER, "SkillHub Public Catalog", client.isEnabled())));
    }

    @GetMapping("/external-skills/{provider}")
    @RateLimit(category = "search", authenticated = 60, anonymous = 20)
    public ApiResponse<ExternalSkillSearchResponse> search(@PathVariable String provider,
            @RequestParam(required = false) String q, @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        requireProvider(provider);
        return ok("response.success.read", client.search(q, category, sort, page, size));
    }

    @GetMapping("/external-skills/{provider}/categories")
    public ApiResponse<JsonNode> categories(@PathVariable String provider) {
        requireProvider(provider);
        return ok("response.success.read", client.categories());
    }

    @GetMapping("/external-skills/{provider}/{slug}")
    public ApiResponse<ExternalSkillDetail> detail(@PathVariable String provider, @PathVariable String slug) {
        requireProvider(provider);
        return ok("response.success.read", client.detail(slug));
    }

    @GetMapping("/external-skills/{provider}/{slug}/versions")
    public ApiResponse<List<ExternalSkillVersion>> versions(@PathVariable String provider, @PathVariable String slug) {
        requireProvider(provider);
        return ok("response.success.read", client.versions(slug));
    }

    @GetMapping("/external-skills/{provider}/{slug}/versions/{version}/files")
    public ApiResponse<List<ExternalSkillFile>> files(@PathVariable String provider, @PathVariable String slug,
                                                      @PathVariable String version) {
        requireProvider(provider);
        return ok("response.success.read", client.files(slug, version));
    }

    @GetMapping(value = "/external-skills/{provider}/{slug}/versions/{version}/file", produces = MediaType.TEXT_PLAIN_VALUE)
    public String file(@PathVariable String provider, @PathVariable String slug, @PathVariable String version,
                       @RequestParam String path) {
        requireProvider(provider);
        return client.file(slug, version, path);
    }

    @PostMapping("/external-skills/{provider}/{slug}/versions/{version}/imports/validate")
    public ApiResponse<ExternalSkillImportValidation> validateImport(@PathVariable String provider,
            @PathVariable String slug, @PathVariable String version,
            @Valid @RequestBody ExternalSkillImportRequest request,
            @RequestAttribute("userId") String userId) {
        return ok("response.success.read", importService.validate(provider, slug, version, request, userId));
    }

    @PostMapping("/external-skills/{provider}/{slug}/versions/{version}/imports")
    public ApiResponse<ExternalSkillImportResult> importVersion(@PathVariable String provider,
            @PathVariable String slug, @PathVariable String version,
            @Valid @RequestBody ExternalSkillImportRequest request,
            @RequestAttribute("userId") String userId,
            @RequestAttribute(value = "platformRoles", required = false) Set<String> platformRoles,
            @RequestAttribute(value = "userNsRoles", required = false) Map<Long, NamespaceRole> userNsRoles) {
        return ok("response.success.created", importService.importVersion(provider, slug, version, request,
                userId, platformRoles != null ? platformRoles : Set.of(), userNsRoles != null ? userNsRoles : Map.of()));
    }

    private void requireProvider(String provider) {
        if (!SkillHubCnClient.PROVIDER.equals(provider))
            throw new com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException(
                    "error.externalSkill.provider.unknown", provider);
    }
}
