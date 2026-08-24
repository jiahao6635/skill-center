package com.iflytek.skillhub.domain.usage;

/**
 * Client surface a usage event originated from. UNKNOWN covers writes without
 * an HTTP request (scan auto-publish, review-approved publishVersion, bootstrap).
 */
public enum SkillUsageClient {
    WEB,
    CLI,
    COMPAT,
    API_TOKEN,
    UNKNOWN
}
