package com.iflytek.skillhub.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * UTC-day aggregate per (skill, action). Rebuildable from events still inside
 * the retention window; unique_* lifetime authority stays on skill_usage_actor.
 */
@Entity
@Table(name = "skill_usage_daily")
@IdClass(SkillUsageDailyId.class)
public class SkillUsageDaily {

    @Id
    @Column(name = "skill_id")
    private Long skillId;

    @Id
    private LocalDate day;

    @Id
    @Column(length = 32)
    private String action;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "unique_actors", nullable = false)
    private long uniqueActors;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillUsageDaily() {}

    public Long getSkillId() { return skillId; }
    public LocalDate getDay() { return day; }
    public String getAction() { return action; }
    public long getEventCount() { return eventCount; }
    public long getUniqueActors() { return uniqueActors; }
    public Instant getUpdatedAt() { return updatedAt; }
}
