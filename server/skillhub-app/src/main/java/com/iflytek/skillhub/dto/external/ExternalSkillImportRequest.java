package com.iflytek.skillhub.dto.external;

import jakarta.validation.constraints.NotBlank;

public record ExternalSkillImportRequest(
        @NotBlank String namespace,
        String visibility,
        String packageSha256,
        String warningDigest,
        boolean confirmWarnings,
        boolean confirmMissingLicense) {}
