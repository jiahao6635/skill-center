package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.controller.BaseApiController;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.cli.CliWhoAmIResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import com.iflytek.skillhub.auth.token.ApiTokenGrantContext;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;

@RestController
@RequestMapping("/api/cli/v1/auth")
public class CliAuthController extends BaseApiController {
    private final NamespaceRepository namespaceRepository;

    public CliAuthController(ApiResponseFactory responseFactory, NamespaceRepository namespaceRepository) {
        super(responseFactory);
        this.namespaceRepository = namespaceRepository;
    }

    @GetMapping("/whoami")
    public ApiResponse<CliWhoAmIResponse> whoami(@AuthenticationPrincipal PlatformPrincipal principal, Authentication authentication) {
        ApiTokenGrantContext grant = authentication != null && authentication.getDetails() instanceof ApiTokenGrantContext value ? value : null;
        return ok("response.success.read", new CliWhoAmIResponse(
                principal.userId(),
                principal.displayName(),
                principal.email(), grant != null ? grant.tokenKind() : "SESSION", grant != null ? grant.tokenId() : null,
                grant != null ? grant.tokenPrefix() : null, grant != null ? grant.clientName() : null,
                grant != null ? grant.scopes() : java.util.Set.of(), grant != null ? grant.allowedNamespaceIds() : java.util.Set.of(),
                grant != null ? grant.allowedNamespaceIds().stream().map(namespaceRepository::findById)
                        .flatMap(java.util.Optional::stream)
                        .map(com.iflytek.skillhub.domain.namespace.Namespace::getSlug)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()) : java.util.Set.of(),
                grant != null && grant.expiresAt() != null ? grant.expiresAt().toString() : null
        ));
    }
}
