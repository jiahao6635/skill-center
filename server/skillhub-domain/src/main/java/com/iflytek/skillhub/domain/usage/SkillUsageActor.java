package com.iflytek.skillhub.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Lifetime first-seen / last-seen per (skill, action, actor). Authority for
 * unique downloader / viewer counts; survives event retention deletes. Only
 * DOWNLOAD and VIEW rows are ever written.
 */
@Entity
@Table(name = "skill_usage_actor")
@IdClass(SkillUsageActorId.class)
public class SkillUsageActor {

    @Id
    @Column(name = "skill_id")
    private Long skillId;

    @Id
    @Column(length = 32)
    private String action;

    @Id
    @Column(name = "actor_key", length = 160)
    private String actorKey;

    @Column(name = "first_at", nullable = false)
    private Instant firstAt;

    @Column(name = "last_at", nullable = false)
    private Instant lastAt;

    @Column(name = "last_client", nullable = false, length = 16)
    private String lastClient;

    protected SkillUsageActor() {}

    public Long getSkillId() { return skillId; }
    public String getAction() { return action; }
    public String getActorKey() { return actorKey; }
    public Instant getFirstAt() { return firstAt; }
    public Instant getLastAt() { return lastAt; }
    public String getLastClient() { return lastClient; }
}
