package com.iflytek.skillhub.auth.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RouteSecurityPolicyRegistryTest {

    private static final Set<String> ALL_SCOPES =
            Set.of("skill:read", "skill:publish", "skill:delete", "token:manage");

    private final RouteSecurityPolicyRegistry registry = new RouteSecurityPolicyRegistry();

    @Test
    void authorizeApiToken_requiresPublishScopeForPublishEndpoints() {
        var denied = registry.authorizeApiToken("POST", "/api/web/skills/global/publish", Set.of("skill:read"));
        var allowed = registry.authorizeApiToken("POST", "/api/web/skills/global/publish", Set.of("skill:publish"));

        assertFalse(denied.allowed());
        assertEquals("skill:publish", denied.requiredScope());
        assertTrue(allowed.allowed());
    }

    @Test
    void authorizeApiToken_requiresDeleteScopeForHardDeleteEndpoint() {
        var denied = registry.authorizeApiToken("DELETE", "/api/v1/skills/global/demo-skill", Set.of("skill:publish"));
        var allowed = registry.authorizeApiToken("DELETE", "/api/v1/skills/global/demo-skill", Set.of("skill:delete"));

        assertFalse(denied.allowed());
        assertEquals("skill:delete", denied.requiredScope());
        assertTrue(allowed.allowed());
    }

    @Test
    void authorizationPolicies_shouldDeclareSuperAdminDeleteRuleForHardDeleteEndpoint() {
        boolean matched = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.DELETE
                        && "/api/v1/skills/*/*".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.ROLE_PROTECTED
                        && Set.of(policy.roles()).contains("SUPER_ADMIN"));

        assertTrue(matched);
    }

    @Test
    void authorizationPolicies_shouldKeepPublicLabelsEndpointsAnonymous() {
        boolean matchedV1 = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && "/api/v1/labels".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.PERMIT_ALL);
        boolean matchedWeb = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && "/api/web/labels".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.PERMIT_ALL);

        assertTrue(matchedV1);
        assertTrue(matchedWeb);
    }

    @Test
    void authorizationPolicies_shouldRequireAuthenticationForNamespaceDiscovery() {
        boolean matchedV1 = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && "/api/v1/namespaces".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.AUTHENTICATED);
        boolean matchedWeb = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && "/api/web/namespaces".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.AUTHENTICATED);

        assertTrue(matchedV1);
        assertTrue(matchedWeb);
    }

    @Test
    void authorizationPolicies_shouldNotDeclareNamespaceBundleDownloadRoutes() {
        String v1Route = "/api/v1/namespaces/*/skills/" + "download";
        String webRoute = "/api/web/namespaces/*/skills/" + "download";
        boolean matchedV1 = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && v1Route.equals(policy.pattern()));
        boolean matchedWeb = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && webRoute.equals(policy.pattern()));

        assertFalse(matchedV1);
        assertFalse(matchedWeb);
        assertFalse(registry.authorizeApiToken("GET", "/api/v1/namespaces/global/skills/" + "download", Set.of()).allowed());
        assertFalse(registry.authorizeApiToken("GET", "/api/web/namespaces/global/skills/" + "download", Set.of()).allowed());
    }

    @Test
    void apiTokenPolicySupportsNativeCliRoutes() {
        assertTrue(registry.authorizeApiToken("GET", "/api/cli/v1/auth/whoami", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/cli/v1/skills/search", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/cli/v1/skills/global/demo/resolve", Set.of()).allowed());
        assertFalse(registry.authorizeApiToken("POST", "/api/cli/v1/skills/global/publish", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("POST", "/api/cli/v1/skills/global/publish", Set.of("skill:publish")).allowed());
        assertFalse(registry.authorizeApiToken("POST", "/api/cli/v1/skills/global/publish/validate", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("POST", "/api/cli/v1/skills/global/publish/validate", Set.of("skill:publish")).allowed());
        assertTrue(registry.authorizeApiToken("DELETE", "/api/cli/v1/skills/global/demo", Set.of("skill:delete")).allowed());
    }

    @Test
    void routeAuthorizationProtectsNativeCliRemoteDeleteByAuthenticationNotSuperAdminRole() {
        boolean matched = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.DELETE
                        && "/api/cli/v1/skills/*/*".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.AUTHENTICATED);

        assertTrue(matched);
    }

    @Test
    void authorizationPolicies_shouldDeclareDownloadLinkRoutes() {
        boolean issueMatched = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.POST
                        && "/api/web/skills/*/*/download-link".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.AUTHENTICATED);
        boolean redirectMatched = registry.authorizationPolicies().stream()
                .anyMatch(policy -> policy.method() == HttpMethod.GET
                        && "/api/cli/v1/download-link/*".equals(policy.pattern())
                        && policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.PERMIT_ALL);

        assertTrue(issueMatched);
        assertTrue(redirectMatched);
    }

    @Test
    void authorizationPolicies_shouldNotDeclareLegacyDownloadTokenRoute() {
        boolean matched = registry.authorizationPolicies().stream()
                .anyMatch(policy -> "/api/web/auth/download-token".equals(policy.pattern()));

        assertFalse(matched);
    }

    @Test
    void shouldIgnoreCsrf_onlyForBearerTokensAndDeviceTokenFlow() {
        assertFalse(registry.shouldIgnoreCsrf("POST", "/api/v1/admin/users", null, false));
        assertFalse(registry.shouldIgnoreCsrf("POST", "/api/v1/auth/local/change-password", null, false));
        assertTrue(registry.shouldIgnoreCsrf("POST", "/not-api", "Bearer token", false));
        assertFalse(registry.shouldIgnoreCsrf("POST", "/not-api", "Bearer token", true));
        assertTrue(registry.shouldIgnoreCsrf("POST", "/api/v1/auth/device/code", null, false));
        assertTrue(registry.shouldIgnoreCsrf("POST", "/api/v1/auth/device/token", null, false));
        assertFalse(registry.shouldIgnoreCsrf("GET", "/api/v1/auth/device/code", null, false));
        assertFalse(registry.shouldIgnoreCsrf("POST", "/api/v1/auth/device/authorize", null, false));
        assertFalse(registry.shouldIgnoreCsrf("POST", "/ui/settings", null, false));
    }

    @Test
    void shouldProjectRequestContext_onlyForApiRoutes() {
        assertTrue(registry.shouldProjectRequestContext("/api/web/namespaces/team-a"));
        assertFalse(registry.shouldProjectRequestContext("/assets/index.css"));
    }

    @Test
    void authorizeApiToken_allowsDiscoveryLabelsAndDownloadRoutes() {
        assertTrue(registry.authorizeApiToken("GET", "/api/v1/auth/methods", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/v1/labels", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/web/labels", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/v1/download/global/demo-skill", Set.of()).allowed());
        assertTrue(registry.authorizeApiToken("GET", "/api/cli/v1/download-link/opaque-token", Set.of()).allowed());
    }

    @Test
    void authorizeApiToken_allowsStarAndRatingWritesWithoutDeleteScope() {
        assertTrue(registry.authorizeApiToken("PUT", "/api/v1/skills/42/star", Set.of("skill:read")).allowed());
        assertTrue(registry.authorizeApiToken("DELETE", "/api/v1/skills/42/star", Set.of("skill:read")).allowed());
        assertTrue(registry.authorizeApiToken("PUT", "/api/v1/skills/42/rating", Set.of("skill:read")).allowed());
        assertTrue(registry.authorizeApiToken("PUT", "/api/web/skills/42/star", Set.of("skill:read")).allowed());
        assertTrue(registry.authorizeApiToken("DELETE", "/api/web/skills/42/star", Set.of("skill:read")).allowed());
        assertTrue(registry.authorizeApiToken("PUT", "/api/web/skills/42/rating", Set.of("skill:read")).allowed());
    }

    @Test
    void authorizationPolicies_declareUnstarBeforeHardDeleteRules() {
        int v1Unstar = indexOf(HttpMethod.DELETE, "/api/v1/skills/*/star");
        int v1HardDelete = indexOf(HttpMethod.DELETE, "/api/v1/skills/*/*");
        int webUnstar = indexOf(HttpMethod.DELETE, "/api/web/skills/*/star");
        int webHardDelete = indexOf(HttpMethod.DELETE, "/api/web/skills/*/*");

        assertTrue(v1Unstar >= 0, "v1 unstar must have its own authorization policy");
        assertTrue(webUnstar >= 0, "web unstar must have its own authorization policy");
        assertTrue(v1Unstar < v1HardDelete, "v1 unstar must precede the SUPER_ADMIN hard-delete rule");
        assertTrue(webUnstar < webHardDelete, "web unstar must precede the browser hard-delete rule");
    }

    @Test
    void apiTokenPolicies_coverEveryTokenReachableAuthorizationRoute() {
        List<String> gaps = new ArrayList<>();

        for (RouteSecurityPolicyRegistry.RouteAuthorizationPolicy policy : registry.authorizationPolicies()) {
            if (policy.accessLevel() == RouteSecurityPolicyRegistry.AccessLevel.ROLE_PROTECTED) {
                continue;
            }
            if (!policy.pattern().startsWith("/api/")) {
                continue;
            }
            String key = RouteSecurityPolicyRegistry.routeKey(policy.method(), policy.pattern());
            if (registry.sessionOnlyRoutes().contains(key)) {
                continue;
            }

            String method = policy.method() == null ? "GET" : policy.method().name();
            String path = samplePath(policy.pattern());
            if (!registry.authorizeApiToken(method, path, ALL_SCOPES).allowed()) {
                gaps.add(key);
            }
        }

        assertTrue(gaps.isEmpty(),
                "Authorization routes with no API-token policy and no session-only declaration: " + gaps);
    }

    @Test
    void sessionOnlyRoutes_remainClosedToApiTokens() {
        assertEquals(Set.of(
                "ANY /api/v1/auth/session/bootstrap",
                "ANY /api/v1/auth/direct/login",
                "ANY /api/v1/auth/local/**",
                "ANY /api/v1/admin/**",
                "POST /api/web/skills/*/*/download-link",
                "DELETE /api/web/skills/id/*",
                "DELETE /api/web/skills/*/*"
        ), registry.sessionOnlyRoutes());

        for (String route : registry.sessionOnlyRoutes()) {
            String[] parts = route.split(" ", 2);
            String method = "ANY".equals(parts[0]) ? "POST" : parts[0];
            assertFalse(registry.authorizeApiToken(method, samplePath(parts[1]), ALL_SCOPES).allowed(),
                    "session-only route must stay closed to API tokens: " + route);
        }
    }

    private int indexOf(HttpMethod method, String pattern) {
        List<RouteSecurityPolicyRegistry.RouteAuthorizationPolicy> policies = registry.authorizationPolicies();
        for (int i = 0; i < policies.size(); i++) {
            if (policies.get(i).method() == method && pattern.equals(policies.get(i).pattern())) {
                return i;
            }
        }
        return -1;
    }

    private static String samplePath(String pattern) {
        return pattern.replace("/**", "/sample/leaf").replace("*", "sample");
    }
}
