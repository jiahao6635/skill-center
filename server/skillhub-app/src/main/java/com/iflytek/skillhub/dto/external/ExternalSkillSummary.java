package com.iflytek.skillhub.dto.external;

public record ExternalSkillSummary(
        String provider, String slug, String displayName, String summary, String summaryZh,
        String owner, String version, String category, String iconUrl, String sourceUrl,
        long downloads, long installs, long stars, boolean verified) {}
