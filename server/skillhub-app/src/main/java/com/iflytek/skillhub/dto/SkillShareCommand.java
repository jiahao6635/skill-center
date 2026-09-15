package com.iflytek.skillhub.dto;

import com.iflytek.skillhub.domain.skill.SkillVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SkillShareCommand(@NotNull @Positive Long versionId,
        @NotNull @Positive Long targetNamespaceId, @NotNull SkillVisibility targetVisibility,
        @NotBlank @Size(max = 64) String idempotencyKey, boolean confirmPublic, boolean confirmWarnings) {}
