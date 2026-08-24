package com.iflytek.skillhub.domain.usage;

import java.time.Instant;

/**
 * Domain contract for the lifetime unique-actor table. Only the recorder may
 * write here, and only for DOWNLOAD and VIEW with a non-null skill id.
 */
public interface SkillUsageActorRepository {

    /**
     * Insert or refresh the (skill, action, actor) row: keeps the earliest
     * first_at, moves last_at / last_client to the incoming event.
     */
    void upsert(Long skillId, String action, String actorKey, Instant occurredAt, String client);
}
