package com.iflytek.skillhub.domain.usage;

/**
 * How the request that produced a usage event was authenticated. Device-flow
 * tokens are plain API tokens and are recorded as API_TOKEN.
 */
public enum SkillUsageAuthMethod {
    SESSION,
    API_TOKEN,
    ANONYMOUS,
    UNKNOWN
}
