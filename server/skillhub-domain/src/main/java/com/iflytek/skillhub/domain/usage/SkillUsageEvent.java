package com.iflytek.skillhub.domain.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * Append-only usage ledger row. Inserts go through the recorder's native
 * ON CONFLICT insert; this entity exists for read-side projections.
 */
@Entity
@Table(name = "skill_usage_event")
public class SkillUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "skill_id")
    private Long skillId;

    @Column(name = "skill_version_id")
    private Long skillVersionId;

    @Column(name = "namespace_id")
    private Long namespaceId;

    @Column(name = "actor_user_id", length = 128)
    private String actorUserId;

    @Column(name = "actor_key", nullable = false, length = 160)
    private String actorKey;

    @Column(name = "actor_kind", nullable = false, length = 16)
    private String actorKind;

    @Column(nullable = false, length = 16)
    private String client;

    @Column(name = "auth_method", nullable = false, length = 16)
    private String authMethod;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "dedup_key", length = 512)
    private String dedupKey;

    @Column(name = "payload_json", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SkillUsageEvent() {}

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getAction() { return action; }
    public Long getSkillId() { return skillId; }
    public Long getSkillVersionId() { return skillVersionId; }
    public Long getNamespaceId() { return namespaceId; }
    public String getActorUserId() { return actorUserId; }
    public String getActorKey() { return actorKey; }
    public String getActorKind() { return actorKind; }
    public String getClient() { return client; }
    public String getAuthMethod() { return authMethod; }
    public String getRequestId() { return requestId; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public String getDedupKey() { return dedupKey; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
