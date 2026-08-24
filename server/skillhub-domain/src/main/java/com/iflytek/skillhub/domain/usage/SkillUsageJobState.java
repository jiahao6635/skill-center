package com.iflytek.skillhub.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Watermark and advisory lock row for usage background jobs (rollup, retention).
 * Locking is a conditional UPDATE with a lease released in finally by owner.
 */
@Entity
@Table(name = "skill_usage_job_state")
public class SkillUsageJobState {

    @Id
    @Column(name = "job_name", length = 64)
    private String jobName;

    @Column(name = "watermark")
    private Instant watermark;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillUsageJobState() {}

    public String getJobName() { return jobName; }
    public Instant getWatermark() { return watermark; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getLockedBy() { return lockedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
