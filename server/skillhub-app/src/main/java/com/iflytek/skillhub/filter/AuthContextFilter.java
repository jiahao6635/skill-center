package com.iflytek.skillhub.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenGrantContext;
import com.iflytek.skillhub.security.RequestAuthorizationContext;
import com.iflytek.skillhub.domain.namespace.NamespaceMember;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Projects the authenticated principal into request attributes consumed by the controller layer.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class AuthContextFilter extends OncePerRequestFilter {

    private final NamespaceMemberRepository namespaceMemberRepository;
    private final UserAccountRepository userAccountRepository;
    private final ApiResponseFactory apiResponseFactory;
    private final ObjectMapper objectMapper;
    private final boolean enforceActiveUserCheck;
    private final RouteSecurityPolicyRegistry routeSecurityPolicyRegistry;
    private final NamespaceRepository namespaceRepository;

    public AuthContextFilter(NamespaceMemberRepository namespaceMemberRepository,
                             UserAccountRepository userAccountRepository,
                             ApiResponseFactory apiResponseFactory,
                             ObjectMapper objectMapper,
                             @Value("${skillhub.auth.enforce-active-user-check:true}") boolean enforceActiveUserCheck,
                             RouteSecurityPolicyRegistry routeSecurityPolicyRegistry,
                             NamespaceRepository namespaceRepository) {
        this.namespaceMemberRepository = namespaceMemberRepository;
        this.userAccountRepository = userAccountRepository;
        this.apiResponseFactory = apiResponseFactory;
        this.objectMapper = objectMapper;
        this.enforceActiveUserCheck = enforceActiveUserCheck;
        this.routeSecurityPolicyRegistry = routeSecurityPolicyRegistry;
        this.namespaceRepository = namespaceRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        clearAuthorizationMdc();
        if (!routeSecurityPolicyRegistry.shouldProjectRequestContext(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        PlatformPrincipal principal = resolvePrincipal(request);
        if (principal != null) {
            if (isInactiveUser(principal.userId())) {
                clearAuthentication(request);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(
                        response.getOutputStream(),
                        apiResponseFactory.error(HttpServletResponse.SC_UNAUTHORIZED, "error.auth.local.accountDisabled")
                );
                return;
            }
            request.setAttribute("userId", principal.userId());
            request.setAttribute("platformRoles", principal.platformRoles() != null ? principal.platformRoles() : java.util.Set.of());
            Map<Long, NamespaceRole> userNsRoles = namespaceMemberRepository.findByUserId(principal.userId()).stream()
                    .collect(Collectors.toMap(
                            NamespaceMember::getNamespaceId,
                            NamespaceMember::getRole,
                            (left, right) -> left));
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            ApiTokenGrantContext tokenGrant = authentication != null && authentication.getDetails() instanceof ApiTokenGrantContext grant
                    ? grant : null;
            if (tokenGrant != null && tokenGrant.isAgent()) {
                userNsRoles = userNsRoles.entrySet().stream()
                        .filter(entry -> tokenGrant.allowedNamespaceIds().contains(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
            boolean anonymousProjection = tokenGrant != null && tokenGrant.isAgent()
                    && shouldDowngradeAgentRead(request, tokenGrant);
            if (anonymousProjection) {
                request.removeAttribute("userId");
                request.setAttribute("platformRoles", java.util.Set.of());
                request.setAttribute("userNsRoles", Map.of());
            } else {
                request.setAttribute("userNsRoles", userNsRoles);
            }
            request.setAttribute("authorizationContext", new RequestAuthorizationContext(
                    principal.userId(), principal.platformRoles() != null ? principal.platformRoles() : java.util.Set.of(),
                    userNsRoles, tokenGrant != null ? "API_TOKEN" : "SESSION", tokenGrant));
            MDC.put("authSource", tokenGrant != null ? "API_TOKEN" : "SESSION");
            if (tokenGrant != null) {
                if (tokenGrant.tokenId() != null) MDC.put("tokenId", tokenGrant.tokenId().toString());
                if (tokenGrant.tokenPrefix() != null) MDC.put("tokenPrefix", tokenGrant.tokenPrefix());
                if (tokenGrant.clientName() != null) MDC.put("clientName", tokenGrant.clientName());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            clearAuthorizationMdc();
        }
    }

    private boolean isInactiveUser(String userId) {
        if (!enforceActiveUserCheck) {
            return false;
        }
        return userAccountRepository.findById(userId)
                .map(user -> !user.isActive())
                .orElse(true);
    }

    private void clearAuthentication(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        session.removeAttribute("platformPrincipal");
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        session.invalidate();
    }

    private PlatformPrincipal resolvePrincipal(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof PlatformPrincipal platformPrincipal) {
                return platformPrincipal;
            }
        }

        Object sessionPrincipal = request.getSession(false) != null
                ? request.getSession(false).getAttribute("platformPrincipal")
                : null;
        if (sessionPrincipal instanceof PlatformPrincipal platformPrincipal) {
            return platformPrincipal;
        }
        return null;
    }

    private void clearAuthorizationMdc() {
        MDC.remove("authSource");
        MDC.remove("tokenId");
        MDC.remove("tokenPrefix");
        MDC.remove("clientName");
        MDC.remove("authorizedNamespaceId");
    }

    private boolean shouldDowngradeAgentRead(HttpServletRequest request, ApiTokenGrantContext grant) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        if (path == null || path.startsWith("/api/cli/v1/manage/")
                || path.startsWith("/api/cli/v1/external/")) return false;
        if (path.equals("/api/v1/skills") || path.equals("/api/v1/search")
                || path.equals("/api/cli/v1/skills/search")) return true;
        String prefix = path.startsWith("/api/cli/v1/skills/")
                ? "/api/cli/v1/skills/" : path.startsWith("/api/v1/skills/")
                ? "/api/v1/skills/" : null;
        if (prefix == null) return false;
        String remainder = path.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) return true;
        String namespaceSlug = remainder.substring(0, slash);
        Long namespaceId = namespaceRepository.findBySlug(namespaceSlug)
                .map(com.iflytek.skillhub.domain.namespace.Namespace::getId)
                .orElse(null);
        return namespaceId == null || !grant.allowedNamespaceIds().contains(namespaceId);
    }
}
