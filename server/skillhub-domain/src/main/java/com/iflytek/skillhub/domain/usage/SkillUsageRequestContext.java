package com.iflytek.skillhub.domain.usage;

/**
 * Request-scoped context captured at the HTTP edge and carried into the domain
 * as a plain value. Never resolved from ThreadLocal or request-scoped beans.
 */
public record SkillUsageRequestContext(
        SkillUsageClient client,
        SkillUsageAuthMethod authMethod,
        String requestId,
        String clientIp,
        String userAgent
) {}
