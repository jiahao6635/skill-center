package com.iflytek.skillhub.security;

import com.iflytek.skillhub.auth.token.ApiTokenGrantContext;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenGrantGuardTest {
    private final TokenGrantGuard guard = new TokenGrantGuard();

    @Test
    void agentGrantCannotCrossNamespaceEvenForSuperAdmin() {
        RequestAuthorizationContext context = context("AGENT", Set.of("skill:lifecycle"), Set.of(11L));

        assertThatThrownBy(() -> guard.requireAgent(context, "skill:lifecycle", 22L))
                .isInstanceOf(DomainForbiddenException.class);
    }

    @Test
    void personalTokenCannotUseAgentOnlySurface() {
        RequestAuthorizationContext context = context("PERSONAL", Set.of("review:read"), Set.of());

        assertThatThrownBy(() -> guard.requireAgent(context, "review:read", 11L))
                .isInstanceOf(DomainForbiddenException.class);
    }

    @Test
    void matchingAgentScopeAndNamespacePasses() {
        RequestAuthorizationContext context = context("AGENT", Set.of("review:decide"), Set.of(11L));

        assertThatCode(() -> guard.requireAgent(context, "review:decide", 11L))
                .doesNotThrowAnyException();
    }

    private RequestAuthorizationContext context(String kind, Set<String> scopes, Set<Long> namespaces) {
        return new RequestAuthorizationContext(
                "super-user",
                Set.of("SUPER_ADMIN"),
                Map.of(11L, NamespaceRole.OWNER, 22L, NamespaceRole.OWNER),
                "API_TOKEN",
                new ApiTokenGrantContext(1L, "sk_test", "agent", kind, scopes, namespaces,
                        Instant.parse("2026-09-01T00:00:00Z")));
    }
}
