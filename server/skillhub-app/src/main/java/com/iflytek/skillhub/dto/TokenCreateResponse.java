package com.iflytek.skillhub.dto;

public record TokenCreateResponse(
        String token,
        Long id,
        String name,
        String tokenPrefix,
        String createdAt,
        String expiresAt,
        String tokenKind,
        java.util.List<String> scopes,
        String namespace,
        String clientName
) {
    public TokenCreateResponse(String token, Long id, String name, String tokenPrefix, String createdAt, String expiresAt) {
        this(token, id, name, tokenPrefix, createdAt, expiresAt, "PERSONAL", java.util.List.of(), "", "");
    }
}
