package com.iflytek.skillhub.dto.cli;

import java.util.List;

public record CliDryRunResponse(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String resolvedSlug,
        String resolvedVersion,
        String packageFingerprint,
        String warningDigest,
        boolean requiresConfirmation
) {
    public CliDryRunResponse(boolean valid, List<String> errors, List<String> warnings,
                             String resolvedSlug, String resolvedVersion) {
        this(valid, errors, warnings, resolvedSlug, resolvedVersion, null, null, !warnings.isEmpty());
    }
}
