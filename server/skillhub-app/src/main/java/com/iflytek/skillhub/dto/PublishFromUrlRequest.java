package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for publishing a skill package resolved from a remote share or download URL.
 */
public record PublishFromUrlRequest(
        @NotBlank(message = "Share URL is required")
        String url,
        @NotBlank(message = "Visibility is required")
        String visibility,
        Boolean confirmWarnings
) {
    public boolean confirmWarningsOrDefault() {
        return Boolean.TRUE.equals(confirmWarnings);
    }
}
