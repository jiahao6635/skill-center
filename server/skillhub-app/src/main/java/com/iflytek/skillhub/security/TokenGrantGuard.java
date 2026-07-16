package com.iflytek.skillhub.security;

import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

@Component
public class TokenGrantGuard {
    public void require(RequestAuthorizationContext context, String scope, Long namespaceId) {
        if (context == null || context.tokenGrant() == null) return;
        var grant = context.tokenGrant();
        if (!grant.scopes().contains(scope)) throw new DomainForbiddenException("error.token.scope.missing", scope);
        if (grant.isAgent() && !grant.allowedNamespaceIds().contains(namespaceId))
            throw new DomainForbiddenException("error.token.namespace.notAllowed");
        if (namespaceId != null) MDC.put("authorizedNamespaceId", namespaceId.toString());
    }

    public void requireAgent(RequestAuthorizationContext context, String scope, Long namespaceId) {
        if (context == null || context.tokenGrant() == null || !context.tokenGrant().isAgent()) {
            throw new DomainForbiddenException("error.token.agent.required");
        }
        require(context, scope, namespaceId);
    }
}
