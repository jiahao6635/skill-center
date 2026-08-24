package com.iflytek.skillhub.domain.usage;

import java.time.Instant;

/**
 * Domain contract for appending usage ledger rows.
 */
public interface SkillUsageEventRepository {

    /**
     * Insert one usage event. Returns 0 when {@code dedupKey} collides with the
     * partial unique index (a window duplicate, treated as a successful dedup);
     * 1 when the row was inserted. Any other integrity violation propagates.
     */
    int insert(Instant occurredAt,
               String action,
               Long skillId,
               Long skillVersionId,
               Long namespaceId,
               String actorUserId,
               String actorKey,
               String actorKind,
               String client,
               String authMethod,
               String requestId,
               String clientIp,
               String userAgent,
               String dedupKey,
               String payloadJson);
}
