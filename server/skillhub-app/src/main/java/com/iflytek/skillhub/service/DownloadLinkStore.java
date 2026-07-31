package com.iflytek.skillhub.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed store for short-lived deep-link download tokens.
 *
 * <p>Each token maps to the presigned object-storage URL plus the identifiers
 * needed to record the download when QoderWork actually fetches the package.
 * Entries expire together with the presigned URL (10 minutes) so a stale token
 * can never outlive the URL it points to.
 *
 * <p>Tokens are unguessable (random UUID), which makes the public redirect
 * endpoint safe even for private skills: possessing a token is the only
 * capability required to download.
 */
@Component
public class DownloadLinkStore {

    static final String KEY_PREFIX = "skillhub:download-link:";
    static final String COUNTED_KEY_PREFIX = "skillhub:download-link-counted:";
    static final long TTL_MINUTES = 10;

    private final RedisTemplate<String, Object> redisTemplate;

    public DownloadLinkStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void save(String token, DownloadLinkData data) {
        redisTemplate.opsForValue().set(KEY_PREFIX + token, data, TTL_MINUTES, TimeUnit.MINUTES);
    }

    public DownloadLinkData get(String token) {
        Object value = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        return value instanceof DownloadLinkData data ? data : null;
    }

    /**
     * Atomically marks a token as already counted, returning {@code true} only
     * for the first caller within the TTL window.
     *
     * <p>Backed by Redis {@code SETNX} with the same TTL as the token itself.
     * This makes download counting idempotent: QoderWork retries, prefetches or
     * network re-sends of the same token still redirect successfully but only
     * ever record a single download. The marker is deliberately a separate key
     * (not a destructive consume of the token) so redirects remain repeatable.
     */
    public boolean markCountedIfAbsent(String token) {
        Boolean set = redisTemplate.opsForValue()
                .setIfAbsent(COUNTED_KEY_PREFIX + token, Boolean.TRUE, TTL_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(set);
    }

    /**
     * Redis-serializable payload behind a download token.
     *
     * <p>Plain bean with a no-arg constructor so the shared
     * {@code GenericJackson2JsonRedisSerializer} can round-trip it.
     */
    public static class DownloadLinkData implements Serializable {
        private String presignedUrl;
        private Long skillId;
        private Long versionId;
        private String filename;
        private boolean published;

        public DownloadLinkData() {}

        public DownloadLinkData(String presignedUrl, Long skillId, Long versionId, String filename, boolean published) {
            this.presignedUrl = presignedUrl;
            this.skillId = skillId;
            this.versionId = versionId;
            this.filename = filename;
            this.published = published;
        }

        public String getPresignedUrl() { return presignedUrl; }
        public Long getSkillId() { return skillId; }
        public Long getVersionId() { return versionId; }
        public String getFilename() { return filename; }
        public boolean isPublished() { return published; }
    }
}
