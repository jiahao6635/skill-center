package com.iflytek.skillhub.auth.token;

import java.time.Instant;
import java.util.Set;

public record ApiTokenGrantContext(Long tokenId, String tokenPrefix, String clientName, String tokenKind,
                                   Set<String> scopes, Set<Long> allowedNamespaceIds, Instant expiresAt) {
    public boolean isAgent() { return "AGENT".equals(tokenKind); }
}
