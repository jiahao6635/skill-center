package com.iflytek.skillhub.domain.skill.service;

import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.event.ReviewApprovedEvent;
import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.skill.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes an existing private version in place, retaining identity and all private history. */
@Service
@Transactional
public class SkillSharingService {
    private final SkillRepository skills;
    private final SkillVersionRepository versions;
    private final SkillShareRequestRepository requests;
    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;
    private final NamespacePublishLock namespaceLock;
    private final SkillVersionDeletionLock skillLock;
    private final SecurityAuditRepository audits;
    private final ReviewTaskRepository reviews;
    private final ReviewPermissionChecker reviewPermissions;
    private final SharingActorPrivileges privileges;
    private final SkillPublicationService publication;
    private final ApplicationEventPublisher events;
    private final AuditLogService auditLog;
    private final Clock clock;

    public SkillSharingService(SkillRepository skills, SkillVersionRepository versions, SkillShareRequestRepository requests,
            NamespaceRepository namespaces, NamespaceMemberRepository members, NamespacePublishLock namespaceLock,
            SkillVersionDeletionLock skillLock, SecurityAuditRepository audits, ReviewTaskRepository reviews,
            ReviewPermissionChecker reviewPermissions, SharingActorPrivileges privileges, SkillPublicationService publication,
            ApplicationEventPublisher events, AuditLogService auditLog, Clock clock) {
        this.skills = skills; this.versions = versions; this.requests = requests; this.namespaces = namespaces;
        this.members = members; this.namespaceLock = namespaceLock; this.skillLock = skillLock; this.audits = audits;
        this.reviews = reviews; this.reviewPermissions = reviewPermissions; this.privileges = privileges;
        this.publication = publication; this.events = events; this.auditLog = auditLog; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Skill requireOwner(Long skillId, String actor) {
        Skill skill = skills.findById(skillId).orElseThrow(() -> new DomainNotFoundException("sharing.notFound"));
        if (!Objects.equals(skill.getOwnerId(), actor)) throw new DomainForbiddenException("sharing.ownerOnly");
        return skill;
    }

    @Transactional(readOnly = true)
    public void validateTarget(Skill skill, Long versionId, Long targetId, SkillVisibility visibility, String actor) {
        if (!Objects.equals(skill.getOwnerId(), actor)) throw new DomainForbiddenException("sharing.ownerOnly");
        if (skill.getStatus() != SkillStatus.ACTIVE || skill.isHidden()) fail("sharing.skillUnavailable");
        Namespace source = namespace(skill.getNamespaceId());
        Namespace target = namespace(targetId);
        if (source.getStatus() != NamespaceStatus.ACTIVE || target.getStatus() != NamespaceStatus.ACTIVE) fail("sharing.namespaceUnavailable");
        if (target.getType() == NamespaceType.SYSTEM || visibility == null || visibility == SkillVisibility.PRIVATE
                || (target.getType() != NamespaceType.GLOBAL && visibility != SkillVisibility.NAMESPACE_ONLY)) fail("sharing.invalidScope");
        if (!privileges.isSuperAdmin(actor) && members.findByNamespaceIdAndUserId(targetId, actor).isEmpty()) {
            throw new DomainForbiddenException("sharing.targetPermission");
        }
        if (skill.getVisibility() != SkillVisibility.PRIVATE
                && (!Objects.equals(skill.getNamespaceId(), targetId) || skill.getVisibility() != visibility)) fail("sharing.scopeFixed");
        if (target.getType() == NamespaceType.GLOBAL && skill.getSlug().contains("--")) fail("sharing.invalidGlobalSlug");
        SkillVersion version = version(skill, versionId);
        if (version.getStatus() != SkillVersionStatus.UPLOADED && version.getStatus() != SkillVersionStatus.PUBLISHED
                && version.getStatus() != SkillVersionStatus.DRAFT) fail("sharing.versionUnavailable");
        if (!VersionAccessPolicy.isPrivate(skill, version)) fail("sharing.alreadyShared");
        if (!version.isBundleReady() || !version.isDownloadReady()) fail("sharing.packageUnavailable");
        if (skills.findByNamespaceIdAndSlug(targetId, skill.getSlug()).stream()
                .anyMatch(other -> !Objects.equals(other.getId(), skill.getId()))) fail("sharing.nameConflict");
    }

    public SkillShareRequest submit(Long skillId, Long versionId, Long targetId, SkillVisibility visibility,
                                   String actor, String key, boolean confirmPublic) {
        namespaceLock.lock(targetId);
        Skill skill = skillLock.lockAndRefresh(skillId).orElseThrow(() -> new DomainNotFoundException("sharing.notFound"));
        if (!Objects.equals(actor, skill.getOwnerId())) throw new DomainForbiddenException("sharing.ownerOnly");
        var previous = requests.findBySkillIdAndIdempotencyKey(skillId, key);
        if (previous.isPresent()) {
            SkillShareRequest existing = previous.get();
            if (!Objects.equals(existing.getSkillVersionId(), versionId) || !Objects.equals(existing.getTargetNamespaceId(), targetId)
                    || existing.getTargetVisibility() != visibility) fail("sharing.idempotencyConflict");
            return existing;
        }
        if (visibility == SkillVisibility.PUBLIC && !confirmPublic) fail("sharing.confirmPublic");
        if (requests.findFirstBySkillIdOrderByIdDesc(skillId).filter(r -> r.getStatus().isActive()).isPresent()) fail("sharing.activeRequest");
        validateTarget(skill, versionId, targetId, visibility, actor);
        SkillVersion version = version(skill, versionId);
        version.assertNotSharing();
        SkillShareRequest saved = requests.save(new SkillShareRequest(skill, versionId, targetId, visibility, actor, key, now()));
        version.setSharingRequestId(saved.getId());
        versions.save(version);
        record(saved, "SUBMIT_SKILL_SHARE", actor);
        return saved;
    }

    public SkillShareRequest advance(Long requestId) {
        SkillShareRequest request = lockedRequest(requestId);
        if (!request.getStatus().isActive()) return request;
        Skill skill = skills.findById(request.getSkillId()).orElseThrow();
        try {
            validateTarget(skill, request.getSkillVersionId(), request.getTargetNamespaceId(), request.getTargetVisibility(), request.getSubmittedBy());
        } catch (LocalizedDomainException ex) {
            return finish(request, SkillShareStatus.FAILED, ex.messageCode(), request.getSubmittedBy());
        }
        if (request.getStatus() != SkillShareStatus.SCANNING) return request;
        SecurityAudit audit = request.getSecurityAuditId() == null ? null : audits.findById(request.getSecurityAuditId()).orElse(null);
        if (audit == null || audit.getScannedAt() == null) {
            if (Duration.between(request.getCreatedAt(), now()).compareTo(Duration.ofMinutes(15)) > 0) {
                return finish(request, SkillShareStatus.FAILED, "sharing.scanTimeout", request.getSubmittedBy());
            }
            return request;
        }
        if (audit.isDeleted() || !Objects.equals(audit.getSkillVersionId(), request.getSkillVersionId())
                || audit.getVerdict() != SecurityVerdict.SAFE) {
            return finish(request, SkillShareStatus.FAILED, "sharing.scanUnsafe", request.getSubmittedBy());
        }
        if (privileges.isSuperAdmin(request.getSubmittedBy())) {
            complete(request, skill, request.getSubmittedBy());
        } else {
            ReviewTask task = new ReviewTask(request.getSkillVersionId(), request.getTargetNamespaceId(), request.getSubmittedBy());
            task.setShareRequestId(request.getId());
            task = reviews.save(task);
            request.setReviewTaskId(task.getId());
            request.transition(SkillShareStatus.PENDING_REVIEW, null, now());
            requests.save(request);
            events.publishEvent(new ReviewSubmittedEvent(task.getId(), skill.getId(), request.getSkillVersionId(),
                    request.getSubmittedBy(), request.getTargetNamespaceId()));
        }
        return request;
    }

    public SkillShareRequest withdraw(Long skillId, Long requestId, String actor) {
        SkillShareRequest request = lockedRequest(requestId);
        if (!Objects.equals(skillId, request.getSkillId()) || !Objects.equals(actor, request.getSubmittedBy())) {
            throw new DomainForbiddenException("sharing.ownerOnly");
        }
        if (request.getStatus() == SkillShareStatus.WITHDRAWN) return request;
        if (!request.getStatus().isActive()) fail("sharing.notActive");
        return finish(request, SkillShareStatus.WITHDRAWN, null, actor);
    }

    public ReviewTask decide(ReviewTask task, String reviewer, String comment, Map<Long, NamespaceRole> roles,
                             Set<String> platformRoles, boolean approve) {
        SkillShareRequest request = lockedRequest(task.getShareRequestId());
        if (request.getStatus() != SkillShareStatus.PENDING_REVIEW || !Objects.equals(request.getReviewTaskId(), task.getId())) fail("sharing.notActive");
        Namespace target = namespace(request.getTargetNamespaceId());
        if (!reviewPermissions.canReview(task, reviewer, target.getType(), roles, platformRoles)) {
            throw new DomainForbiddenException("review.no_permission");
        }
        Skill skill = skills.findById(request.getSkillId()).orElseThrow();
        if (approve) {
            validateTarget(skill, request.getSkillVersionId(), request.getTargetNamespaceId(), request.getTargetVisibility(), request.getSubmittedBy());
            SecurityAudit audit = audits.findById(request.getSecurityAuditId()).orElseThrow();
            if (audit.isDeleted() || audit.getScannedAt() == null || audit.getVerdict() != SecurityVerdict.SAFE) fail("sharing.scanUnsafe");
        }
        ReviewTaskStatus status = approve ? ReviewTaskStatus.APPROVED : ReviewTaskStatus.REJECTED;
        if (reviews.updateStatusWithVersion(task.getId(), status, reviewer, comment, task.getVersion()) == 0) {
            throw new ConcurrentModificationException("Sharing review changed concurrently");
        }
        // The bulk update owns the review row. Return a detached presentation copy through the caller.
        if (approve) {
            complete(request, skill, reviewer);
            events.publishEvent(new ReviewApprovedEvent(task.getId(), skill.getId(), request.getSkillVersionId(), reviewer, request.getSubmittedBy()));
        } else {
            close(request, SkillShareStatus.REJECTED, null);
            record(request, "REJECT_SKILL_SHARE", reviewer);
            events.publishEvent(new ReviewRejectedEvent(task.getId(), skill.getId(), request.getSkillVersionId(), reviewer, request.getSubmittedBy(), comment));
        }
        return task;
    }

    private void complete(SkillShareRequest request, Skill skill, String actor) {
        SkillVersion version = version(skill, request.getSkillVersionId());
        skill.shareToNamespace(request.getTargetNamespaceId());
        version.setRequestedVisibility(request.getTargetVisibility());
        publication.publishVersion(skill, version, actor);
        close(request, SkillShareStatus.COMPLETED, null);
        record(request, "COMPLETE_SKILL_SHARE", actor);
    }

    private SkillShareRequest finish(SkillShareRequest request, SkillShareStatus status, String error, String actor) {
        if (request.getReviewTaskId() != null) reviews.findById(request.getReviewTaskId())
                .filter(task -> task.getStatus() == ReviewTaskStatus.PENDING).ifPresent(reviews::delete);
        close(request, status, error);
        record(request, "CLOSE_SKILL_SHARE", actor);
        return request;
    }

    private void close(SkillShareRequest request, SkillShareStatus status, String error) {
        request.transition(status, error, now());
        requests.save(request);
        SkillVersion version = versions.findById(request.getSkillVersionId()).orElseThrow();
        if (Objects.equals(version.getSharingRequestId(), request.getId())) version.setSharingRequestId(null);
        versions.save(version);
    }

    private SkillShareRequest lockedRequest(Long id) {
        SkillShareRequest request = requests.findById(id).orElseThrow(() -> new DomainNotFoundException("sharing.notFound"));
        namespaceLock.lock(request.getTargetNamespaceId());
        skillLock.lockAndRefresh(request.getSkillId()).orElseThrow(() -> new DomainNotFoundException("sharing.notFound"));
        requests.refresh(request);
        return request;
    }

    private SkillVersion version(Skill skill, Long versionId) {
        SkillVersion version = versions.findById(versionId).orElseThrow(() -> new DomainNotFoundException("sharing.versionUnavailable"));
        if (!Objects.equals(skill.getId(), version.getSkillId())) fail("sharing.versionUnavailable");
        return version;
    }
    private Namespace namespace(Long id) { return namespaces.findById(id).orElseThrow(() -> new DomainNotFoundException("sharing.namespaceUnavailable")); }
    private Instant now() { return Instant.now(clock); }
    private void fail(String key) { throw new DomainBadRequestException(key); }
    private void record(SkillShareRequest request, String action, String actor) {
        auditLog.record(actor, action, "SKILL_SHARE", request.getId(), request.getIdempotencyKey(), null, null,
                "{\"skillId\":" + request.getSkillId() + ",\"versionId\":" + request.getSkillVersionId()
                        + ",\"sourceNamespaceId\":" + request.getSourceNamespaceId() + ",\"targetNamespaceId\":"
                        + request.getTargetNamespaceId() + ",\"visibility\":\"" + request.getTargetVisibility() + "\"}");
    }
}
