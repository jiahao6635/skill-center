package com.iflytek.skillhub.dto.external;

public record ExternalSkillImportResult(
        String outcome, String namespace, String slug, String version, Long skillId, Long versionId,
        String status, String nextAction) {}
