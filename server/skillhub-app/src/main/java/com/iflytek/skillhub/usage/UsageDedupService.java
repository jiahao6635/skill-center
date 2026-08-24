package com.iflytek.skillhub.usage;

import com.iflytek.skillhub.config.SkillUsageProperties;
import com.iflytek.skillhub.domain.usage.SkillUsageAction;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis SETNX window dedup for usage events. Redis being unavailable degrades
 * to "record it": the partial unique index on skill_usage_event.dedup_key is
 * the durable backstop, so duplicates are still collapsed in PostgreSQL.
 */
@Component
public class UsageDedupService {

    private static final Logger log = LoggerFactory.getLogger(UsageDedupService.class);
    private static final String KEY_PREFIX = "skillhub:usage-dedup:v1:";

    private final StringRedisTemplate redisTemplate;
    private final SkillUsageProperties properties;

    public UsageDedupService(StringRedisTemplate redisTemplate, SkillUsageProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Returns true when this (action, subject, actor) is first in its window
     * and should be recorded; false on a window duplicate.
     */
    public boolean tryAcquire(SkillUsageAction action, String subject, String actorKey) {
        String key = KEY_PREFIX + action.name() + ":" + subject + ":" + actorKey;
        try {
            Boolean set = redisTemplate.opsForValue().setIfAbsent(key, "1", ttlFor(action));
            return !Boolean.FALSE.equals(set);
        } catch (DataAccessException ex) {
            log.warn("Usage dedup unavailable, deferring to DB unique index [action={}]", action, ex);
            return true;
        }
    }

    /**
     * Plaintext PG dedup key: {action}:{subject}:{actor_key}:{bucket}, where
     * bucket is occurred_at floored to the action's dedup window. Fits
     * VARCHAR(512) even with a 128-char user id.
     */
    public String dedupKey(SkillUsageAction action, String subject, String actorKey, Instant occurredAt) {
        long bucket = Math.floorDiv(occurredAt.getEpochSecond(), ttlFor(action).toSeconds());
        return action.name() + ":" + subject + ":" + actorKey + ":" + bucket;
    }

    private Duration ttlFor(SkillUsageAction action) {
        return switch (action) {
            case DOWNLOAD -> properties.getDownloadDedupTtl();
            case VIEW -> properties.getViewDedupTtl();
            case SEARCH -> properties.getSearchDedupTtl();
            case UPLOAD, UPDATE, PUBLISH -> properties.getMutationDedupTtl();
        };
    }
}
