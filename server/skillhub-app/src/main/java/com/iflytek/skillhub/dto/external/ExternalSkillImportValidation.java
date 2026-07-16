package com.iflytek.skillhub.dto.external;

import java.util.List;

public record ExternalSkillImportValidation(
        boolean valid, String packageSha256, String targetSlug, String targetVersion,
        List<String> errors, List<String> warnings, String warningDigest,
        String licenseStatus, String licenseExpression, String lineageStatus,
        boolean metadataNameDiffers, boolean metadataVersionDiffers) {}
