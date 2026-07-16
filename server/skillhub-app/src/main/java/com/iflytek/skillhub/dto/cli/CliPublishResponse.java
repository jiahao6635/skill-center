package com.iflytek.skillhub.dto.cli;

public record CliPublishResponse(
        String namespace,
        String slug,
        String version,
        String visibility,
        Long versionId,
        String status,
        String nextAction
) {
    public CliPublishResponse(String namespace, String slug, String version, String visibility) {
        this(namespace, slug, version, visibility, null, null, null);
    }
}
