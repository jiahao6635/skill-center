package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SkillShareCommand(@NotNull @Positive Long targetNamespaceId,
        @NotBlank @Size(max = 64) String idempotencyKey, boolean confirmWarnings) {}
