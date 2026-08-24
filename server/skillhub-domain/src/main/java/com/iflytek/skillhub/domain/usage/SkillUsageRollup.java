package com.iflytek.skillhub.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-skill usage rollup refreshed by the rollup task (eventually consistent,
 * ~60s). unique_* columns are computed from skill_usage_actor only.
 */
@Entity
@Table(name = "skill_usage_rollup")
public class SkillUsageRollup {

    @Id
    @Column(name = "skill_id")
    private Long skillId;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "unique_downloaders", nullable = false)
    private long uniqueDownloaders;

    @Column(name = "unique_viewers", nullable = false)
    private long uniqueViewers;

    @Column(name = "upload_count", nullable = false)
    private long uploadCount;

    @Column(name = "update_count", nullable = false)
    private long updateCount;

    @Column(name = "last_downloaded_at")
    private Instant lastDownloadedAt;

    @Column(name = "last_viewed_at")
    private Instant lastViewedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillUsageRollup() {}

    public Long getSkillId() { return skillId; }
    public long getViewCount() { return viewCount; }
    public long getUniqueDownloaders() { return uniqueDownloaders; }
    public long getUniqueViewers() { return uniqueViewers; }
    public long getUploadCount() { return uploadCount; }
    public long getUpdateCount() { return updateCount; }
    public Instant getLastDownloadedAt() { return lastDownloadedAt; }
    public Instant getLastViewedAt() { return lastViewedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
