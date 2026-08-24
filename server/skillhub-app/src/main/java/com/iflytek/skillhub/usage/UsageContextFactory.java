package com.iflytek.skillhub.usage;

import com.iflytek.skillhub.domain.usage.SkillUsageActorKind;
import com.iflytek.skillhub.domain.usage.SkillUsageAuthMethod;
import com.iflytek.skillhub.domain.usage.SkillUsageClient;
import com.iflytek.skillhub.domain.usage.SkillUsageRequestContext;
import com.iflytek.skillhub.domain.usage.UsageAttribution;
import com.iflytek.skillhub.ratelimit.AnonymousDownloadIdentityService;
import com.iflytek.skillhub.ratelimit.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Assembles {@link UsageAttribution} at the HTTP edge, on the request thread.
 *
 * <p>Anonymous identity reuses the existing download rate-limit cookie: it is
 * only ever peeked here (never minted — {@code resolve()} stays exclusive to
 * the download rate limiter). A cookie minted earlier in the same request is
 * picked up through the {@code anonymousDownloadIdentity} request attribute;
 * cookieless callers (CLI / compat) fall back to an HMAC(ip, ua) fingerprint.
 */
@Component
public class UsageContextFactory {

    static final String ANONYMOUS_IDENTITY_ATTRIBUTE = "anonymousDownloadIdentity";
    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final AnonymousDownloadIdentityService anonymousDownloadIdentityService;
    private final ClientIpResolver clientIpResolver;

    public UsageContextFactory(AnonymousDownloadIdentityService anonymousDownloadIdentityService,
                               ClientIpResolver clientIpResolver) {
        this.anonymousDownloadIdentityService = anonymousDownloadIdentityService;
        this.clientIpResolver = clientIpResolver;
    }

    public UsageAttribution fromRequest(HttpServletRequest request, String userId) {
        return fromRequest(request, userId, null);
    }

    /**
     * @param clientOverride forced client for callers whose surface cannot be
     *                       derived from the path alone (ClawHub compat)
     */
    public UsageAttribution fromRequest(HttpServletRequest request, String userId,
                                        SkillUsageClient clientOverride) {
        if (request == null) {
            return null;
        }
        SkillUsageClient client = clientOverride != null ? clientOverride : resolveClient(request);
        SkillUsageAuthMethod authMethod = resolveAuthMethod(request, userId);
        String clientIp = clientIpResolver.resolve(request);
        String userAgent = normalizeUserAgent(request.getHeader("User-Agent"));
        SkillUsageRequestContext context = new SkillUsageRequestContext(
                client, authMethod, MDC.get("requestId"), clientIp, userAgent);

        if (userId != null && !userId.isBlank()) {
            return new UsageAttribution(userId, "user:" + userId, SkillUsageActorKind.USER, context);
        }
        String anonymousHash = anonymousCookieHash(request)
                .orElseGet(() -> anonymousDownloadIdentityService.fallbackIdentityHash(clientIp, userAgent));
        return new UsageAttribution(null, "anon:" + anonymousHash, SkillUsageActorKind.ANONYMOUS, context);
    }

    private Optional<String> anonymousCookieHash(HttpServletRequest request) {
        Optional<String> peeked = anonymousDownloadIdentityService.peekCookieHash(request);
        if (peeked.isPresent()) {
            return peeked;
        }
        // Download rate limiting may have minted the cookie on this very request:
        // Set-Cookie lands on the response only, so read the interceptor's attribute.
        Object attribute = request.getAttribute(ANONYMOUS_IDENTITY_ATTRIBUTE);
        if (attribute instanceof AnonymousDownloadIdentityService.AnonymousDownloadIdentity identity
                && identity.cookieHash() != null && !identity.cookieHash().isBlank()) {
            return Optional.of(identity.cookieHash());
        }
        return Optional.empty();
    }

    private SkillUsageClient resolveClient(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && path.startsWith("/api/cli/")) {
            return SkillUsageClient.CLI;
        }
        if (path != null && path.startsWith("/api/web/")) {
            return SkillUsageClient.WEB;
        }
        return hasBearerAuthorization(request) ? SkillUsageClient.API_TOKEN : SkillUsageClient.WEB;
    }

    private SkillUsageAuthMethod resolveAuthMethod(HttpServletRequest request, String userId) {
        if (userId == null || userId.isBlank()) {
            return SkillUsageAuthMethod.ANONYMOUS;
        }
        return hasBearerAuthorization(request)
                ? SkillUsageAuthMethod.API_TOKEN
                : SkillUsageAuthMethod.SESSION;
    }

    private boolean hasBearerAuthorization(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer", 0, "Bearer".length());
    }

    private static String normalizeUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        String normalized = userAgent.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() > MAX_USER_AGENT_LENGTH
                ? normalized.substring(0, MAX_USER_AGENT_LENGTH)
                : normalized;
    }
}
