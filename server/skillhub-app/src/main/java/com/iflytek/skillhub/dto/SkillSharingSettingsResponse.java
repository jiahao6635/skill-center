package com.iflytek.skillhub.dto;

import java.time.Instant;
import java.util.List;

public record SkillSharingSettingsResponse(Long skillId, String slug, String namespace, String visibility,
        String sharedVersion, List<VersionOption> versions, List<TargetOption> targets, SkillShareResponse latestRequest) {
    public record VersionOption(Long id, String version, String status, Instant createdAt, Integer fileCount) {}
    public record TargetOption(Long id, String slug, String displayName, String type) {}
}
