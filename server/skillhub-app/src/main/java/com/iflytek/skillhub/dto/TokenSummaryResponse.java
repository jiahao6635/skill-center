package com.iflytek.skillhub.dto;

public record TokenSummaryResponse(
        Long id,
        String name,
        String tokenPrefix,
        String createdAt,
        String expiresAt,
        String lastUsedAt,
        String tokenKind,
        java.util.List<String> scopes,
        String namespace,
        String clientName
) {
    public TokenSummaryResponse(Long id, String name, String tokenPrefix, String createdAt, String expiresAt, String lastUsedAt) {
        this(id, name, tokenPrefix, createdAt, expiresAt, lastUsedAt, "PERSONAL", java.util.List.of(), "", "");
    }
}
