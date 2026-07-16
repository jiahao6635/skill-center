package com.iflytek.skillhub.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.TokenCreateRequest;
import com.iflytek.skillhub.dto.TokenCreateResponse;
import com.iflytek.skillhub.dto.TokenExpirationUpdateRequest;
import com.iflytek.skillhub.dto.TokenSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.auth.token.ApiTokenScopeService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;

/**
 * Self-service API token management endpoints for authenticated users.
 */
@RestController
@RequestMapping("/api/v1/tokens")
public class TokenController extends BaseApiController {

    private final ApiTokenService apiTokenService;
    private final ObjectMapper objectMapper;
    private final NamespaceRepository namespaceRepository;
    private final ApiTokenScopeService scopeService;
    private final NamespaceMemberRepository namespaceMemberRepository;
    private static final Set<String> AGENT_SCOPES = Set.of(
            "skill:read", "skill:publish", "skill:lifecycle", "review:read", "review:decide");
    private static final Set<String> PERSONAL_SCOPES = Set.of("skill:read", "skill:publish", "token:manage");

    public TokenController(ApiTokenService apiTokenService, ApiResponseFactory responseFactory, ObjectMapper objectMapper,
                           NamespaceRepository namespaceRepository, ApiTokenScopeService scopeService,
                           NamespaceMemberRepository namespaceMemberRepository) {
        super(responseFactory);
        this.apiTokenService = apiTokenService;
        this.objectMapper = objectMapper;
        this.namespaceRepository = namespaceRepository;
        this.scopeService = scopeService;
        this.namespaceMemberRepository = namespaceMemberRepository;
    }

    @PostMapping
    public ApiResponse<TokenCreateResponse> create(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @Valid @RequestBody TokenCreateRequest request) {
        String scopeJson;
        if (request.scopes() == null || request.scopes().isEmpty()) {
            scopeJson = "[\"skill:read\",\"skill:publish\"]";
        } else {
            try {
                scopeJson = objectMapper.writeValueAsString(request.scopes());
            } catch (JsonProcessingException e) {
                scopeJson = "[\"skill:read\",\"skill:publish\"]";
            }
        }

        List<String> requestedScopes = request.scopes() == null || request.scopes().isEmpty()
                ? List.of("skill:read", "skill:publish") : request.scopes();
        boolean agent = "AGENT".equalsIgnoreCase(request.tokenKind());
        if (agent && !AGENT_SCOPES.containsAll(requestedScopes))
            throw new DomainBadRequestException("validation.token.scope.invalid");
        if (!agent && !PERSONAL_SCOPES.containsAll(requestedScopes))
            throw new DomainBadRequestException("validation.token.scope.invalid");
        Long namespaceId = null;
        String namespaceSlug = "";
        if (agent) {
            namespaceSlug = request.namespaceSlug() == null ? "" : request.namespaceSlug().replaceFirst("^@", "");
            var namespace = namespaceRepository.findBySlug(namespaceSlug)
                    .orElseThrow(() -> new DomainBadRequestException("error.namespace.slug.notFound", request.namespaceSlug()));
            namespaceId = namespace.getId();
            boolean superAdmin = principal.platformRoles() != null && principal.platformRoles().contains("SUPER_ADMIN");
            if (!superAdmin && namespaceMemberRepository
                    .findByNamespaceIdAndUserId(namespaceId, principal.userId()).isEmpty()) {
                throw new com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException(
                        "error.namespace.permission.denied");
            }
        }
        var result = agent
                ? apiTokenService.createAgentToken(principal.userId(), request.name(), scopeJson, request.expiresAt(),
                    request.clientId(), request.clientName(), namespaceId)
                : apiTokenService.rotateToken(principal.userId(), request.name(), scopeJson, request.expiresAt());
        return ok("response.success.created", new TokenCreateResponse(
                result.rawToken(),
                result.entity().getId(),
                result.entity().getName(),
                result.entity().getTokenPrefix(),
                formatInstant(result.entity().getCreatedAt()),
                formatInstant(result.entity().getExpiresAt()), result.entity().getTokenKind(), requestedScopes,
                namespaceSlug, result.entity().getClientName()
        ));
    }

    @GetMapping
    public ApiResponse<PageResponse<TokenSummaryResponse>> list(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        var tokens = apiTokenService.listActiveTokens(principal.userId(), page, size);
        var result = tokens.map(t -> new TokenSummaryResponse(
            t.getId(),
            t.getName(),
            t.getTokenPrefix(),
            formatInstant(t.getCreatedAt()),
            formatInstant(t.getExpiresAt()),
            formatInstant(t.getLastUsedAt()), t.getTokenKind(),
            scopeService.parseScopes(t.getScopeJson()).stream().sorted().toList(),
            resolveNamespaceSlug(t.getId()), t.getClientName()
        ));
        return ok("response.success.read", PageResponse.from(result));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @PathVariable Long id) {
        apiTokenService.revokeToken(id, principal.userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/expiration")
    public ApiResponse<TokenSummaryResponse> updateExpiration(
            @AuthenticationPrincipal PlatformPrincipal principal,
            @PathVariable Long id,
            @RequestBody TokenExpirationUpdateRequest request) {
        var token = apiTokenService.updateExpiration(id, principal.userId(), request.expiresAt());
        return ok("response.success.updated", new TokenSummaryResponse(
                token.getId(),
                token.getName(),
                token.getTokenPrefix(),
                formatInstant(token.getCreatedAt()),
                formatInstant(token.getExpiresAt()),
                formatInstant(token.getLastUsedAt()), token.getTokenKind(),
                scopeService.parseScopes(token.getScopeJson()).stream().sorted().toList(),
                resolveNamespaceSlug(token.getId()), token.getClientName()
        ));
    }

    private String formatInstant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private String resolveNamespaceSlug(Long tokenId) {
        return apiTokenService.namespaceGrants(tokenId).stream().findFirst()
                .flatMap(namespaceRepository::findById).map(namespace -> namespace.getSlug()).orElse("");
    }
}
