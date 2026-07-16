package com.iflytek.skillhub.dto.cli;

public record CliWhoAmIResponse(String handle, String displayName, String email, String tokenKind,
                               Long tokenId, String tokenPrefix, String clientName, java.util.Set<String> scopes,
                               java.util.Set<Long> namespaceIds, java.util.Set<String> namespaces, String expiresAt) {
    public CliWhoAmIResponse(String handle, String displayName, String email) {
        this(handle, displayName, email, "SESSION", null, null, null,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), null);
    }
}
