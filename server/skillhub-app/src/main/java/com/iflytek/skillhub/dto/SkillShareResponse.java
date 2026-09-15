package com.iflytek.skillhub.dto;

import java.time.Instant;

public record SkillShareResponse(Long id, String status, Long versionId, String version,
        Long targetNamespaceId, String targetNamespace, String targetDisplayName, String targetVisibility,
        Long reviewTaskId, String reviewComment, String errorCode, Instant createdAt, Instant updatedAt) {}
