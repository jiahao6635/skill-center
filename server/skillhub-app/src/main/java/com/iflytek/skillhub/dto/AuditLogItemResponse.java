package com.iflytek.skillhub.dto;

import java.time.Instant;

public record AuditLogItemResponse(
        Long id,
        String action,
        String userId,
        String username,
        String details,
        String ipAddress,
        String requestId,
        String resourceType,
        String resourceId,
        Instant timestamp,
        String authSource,
        Long tokenId,
        String tokenPrefix,
        String clientName,
        Long authorizedNamespaceId
) {
    public AuditLogItemResponse(Long id, String action, String userId, String username, String details,
                                String ipAddress, String requestId, String resourceType,
                                String resourceId, Instant timestamp) {
        this(id, action, userId, username, details, ipAddress, requestId, resourceType,
                resourceId, timestamp, null, null, null, null, null);
    }
}
