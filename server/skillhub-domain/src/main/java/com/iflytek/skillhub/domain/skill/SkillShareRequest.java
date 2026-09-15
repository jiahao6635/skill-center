package com.iflytek.skillhub.domain.skill;

import jakarta.persistence.*;
import java.time.Instant;

/** A share request references an immutable existing package, without changing its private usage state. */
@Entity
@Table(name = "skill_share_request")
public class SkillShareRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "skill_id", nullable = false)
    private Long skillId;
    @Column(name = "skill_version_id", nullable = false)
    private Long skillVersionId;
    @Column(name = "source_namespace_id", nullable = false)
    private Long sourceNamespaceId;
    @Column(name = "target_namespace_id", nullable = false)
    private Long targetNamespaceId;
    @Enumerated(EnumType.STRING) @Column(name = "target_visibility", nullable = false)
    private SkillVisibility targetVisibility;
    @Column(name = "submitted_by", nullable = false, length = 128)
    private String submittedBy;
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SkillShareStatus status;
    @Column(name = "security_audit_id")
    private Long securityAuditId;
    @Column(name = "review_task_id")
    private Long reviewTaskId;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private int revision;

    protected SkillShareRequest() {}
    public SkillShareRequest(Skill skill, Long versionId, Long targetId, SkillVisibility visibility,
                             String actor, String key, Instant now) {
        skillId = skill.getId(); skillVersionId = versionId; sourceNamespaceId = skill.getNamespaceId();
        targetNamespaceId = targetId; targetVisibility = visibility; submittedBy = actor;
        idempotencyKey = key; status = SkillShareStatus.SCANNING; createdAt = now; updatedAt = now;
    }
    public Long getId() { return id; }
    public Long getSkillId() { return skillId; }
    public Long getSkillVersionId() { return skillVersionId; }
    public Long getSourceNamespaceId() { return sourceNamespaceId; }
    public Long getTargetNamespaceId() { return targetNamespaceId; }
    public SkillVisibility getTargetVisibility() { return targetVisibility; }
    public String getSubmittedBy() { return submittedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public SkillShareStatus getStatus() { return status; }
    public Long getSecurityAuditId() { return securityAuditId; }
    public void setSecurityAuditId(Long id) { securityAuditId = id; }
    public Long getReviewTaskId() { return reviewTaskId; }
    public void setReviewTaskId(Long id) { reviewTaskId = id; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void transition(SkillShareStatus status, String errorCode, Instant now) {
        this.status = status; this.errorCode = errorCode; this.updatedAt = now;
    }
}
