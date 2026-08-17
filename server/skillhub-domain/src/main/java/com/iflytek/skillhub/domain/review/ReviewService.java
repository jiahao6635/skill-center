package com.iflytek.skillhub.domain.review;

import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.namespace.NamespaceStatus;
import com.iflytek.skillhub.domain.event.ReviewApprovedEvent;
import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.governance.GovernanceNotificationService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.service.SkillGovernanceService;
import com.iflytek.skillhub.domain.skill.service.SkillPublicationService;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates the review workflow for skill versions.
 *
 * <p>This service owns transitions between draft, pending review, approved,
 * and rejected states together with the review task record that tracks the
 * moderation decision.
 */
@Service
public class ReviewService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillRepository skillRepository;
    private final NamespaceRepository namespaceRepository;
    private final ReviewPermissionChecker permissionChecker;
    private final ApplicationEventPublisher eventPublisher;
    private final SkillGovernanceService skillGovernanceService;
    private final SkillPublicationService skillPublicationService;
    private final GovernanceNotificationService governanceNotificationService;
    private final EntityManager entityManager;
    private final Clock clock;

    public ReviewService(ReviewTaskRepository reviewTaskRepository,
                         SkillVersionRepository skillVersionRepository,
                         SkillRepository skillRepository,
                         NamespaceRepository namespaceRepository,
                         ReviewPermissionChecker permissionChecker,
                         ApplicationEventPublisher eventPublisher,
                         SkillGovernanceService skillGovernanceService,
                         SkillPublicationService skillPublicationService,
                         GovernanceNotificationService governanceNotificationService,
                         EntityManager entityManager,
                         Clock clock) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillRepository = skillRepository;
        this.namespaceRepository = namespaceRepository;
        this.permissionChecker = permissionChecker;
        this.eventPublisher = eventPublisher;
        this.skillGovernanceService = skillGovernanceService;
        this.skillPublicationService = skillPublicationService;
        this.governanceNotificationService = governanceNotificationService;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Submits a draft version into the review queue using both namespace roles
     * and platform roles to determine permission.
     */
    @Transactional
    public ReviewTask submitReview(Long skillVersionId,
                                   String userId,
                                   Map<Long, NamespaceRole> userNamespaceRoles,
                                   Set<String> platformRoles) {
        SkillVersion skillVersion = skillVersionRepository.findById(skillVersionId)
                .orElseThrow(() -> new DomainNotFoundException("skill_version.not_found", skillVersionId));

        Skill skill = skillRepository.findById(skillVersion.getSkillId())
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillVersion.getSkillId()));
        Namespace namespace = namespaceRepository.findById(skill.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", skill.getNamespaceId()));
        assertNamespaceActive(namespace);

        if (!permissionChecker.canSubmitForReview(skill, userId, userNamespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("review.submit.no_permission");
        }

        // Support both DRAFT (legacy) and UPLOADED (new flow) status
        if (skillVersion.getStatus() != SkillVersionStatus.DRAFT
                && skillVersion.getStatus() != SkillVersionStatus.UPLOADED) {
            throw new DomainBadRequestException("review.submit.not_draft", skillVersionId);
        }

        skillVersion.setStatus(SkillVersionStatus.PENDING_REVIEW);
        skillVersionRepository.save(skillVersion);

        ReviewTask task = new ReviewTask(skillVersionId, skill.getNamespaceId(), userId);
        try {
            ReviewTask saved = reviewTaskRepository.save(task);
            eventPublisher.publishEvent(new ReviewSubmittedEvent(
                    saved.getId(), skillVersion.getSkillId(), skillVersion.getId(),
                    saved.getSubmittedBy(), saved.getNamespaceId()));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new DomainBadRequestException("review.submit.duplicate", skillVersionId);
        }
    }

    /**
     * Legacy overload that evaluates submission rights only from namespace
     * memberships.
     */
    @Transactional
    public ReviewTask submitReview(Long skillVersionId,
                                   String userId,
                                   Map<Long, NamespaceRole> userNamespaceRoles) {
        SkillVersion skillVersion = skillVersionRepository.findById(skillVersionId)
                .orElseThrow(() -> new DomainNotFoundException("skill_version.not_found", skillVersionId));

        Skill skill = skillRepository.findById(skillVersion.getSkillId())
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillVersion.getSkillId()));
        Namespace namespace = namespaceRepository.findById(skill.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", skill.getNamespaceId()));
        assertNamespaceActive(namespace);

        // Support both DRAFT (legacy) and UPLOADED (new flow) status
        if (skillVersion.getStatus() != SkillVersionStatus.DRAFT
                && skillVersion.getStatus() != SkillVersionStatus.UPLOADED) {
            throw new DomainBadRequestException("review.submit.not_draft", skillVersionId);
        }

        if (!permissionChecker.canSubmitReview(skill.getNamespaceId(), userNamespaceRoles)) {
            throw new DomainForbiddenException("review.submit.no_permission");
        }

        skillVersion.setStatus(SkillVersionStatus.PENDING_REVIEW);
        skillVersionRepository.save(skillVersion);

        ReviewTask task = new ReviewTask(skillVersionId, skill.getNamespaceId(), userId);
        try {
            ReviewTask saved = reviewTaskRepository.save(task);
            eventPublisher.publishEvent(new ReviewSubmittedEvent(
                    saved.getId(), skillVersion.getSkillId(), skillVersion.getId(),
                    saved.getSubmittedBy(), saved.getNamespaceId()));
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new DomainBadRequestException("review.submit.duplicate", skillVersionId);
        }
    }

    /**
     * Approves a pending review task, publishes the underlying version, and
     * emits downstream notifications and publication events.
     */
    @Transactional
    public ReviewTask approveReview(Long reviewTaskId, String reviewerId, String comment,
                                    Map<Long, NamespaceRole> userNamespaceRoles,
                                    Set<String> platformRoles) {
        ReviewTask task = reviewTaskRepository.findById(reviewTaskId)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found", reviewTaskId));

        if (task.getStatus() != ReviewTaskStatus.PENDING) {
            throw new DomainBadRequestException("review.not_pending", reviewTaskId);
        }

        Namespace namespace = namespaceRepository.findById(task.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", task.getNamespaceId()));
        assertNamespaceActive(namespace);

        if (!permissionChecker.canReview(task, reviewerId, namespace.getType(),
                userNamespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("review.no_permission");
        }

        SkillVersion skillVersion = skillVersionRepository.findById(task.getSkillVersionId())
                .orElseThrow(() -> new DomainNotFoundException("skill_version.not_found", task.getSkillVersionId()));
        if (skillVersion.getStatus() == SkillVersionStatus.SCANNING) {
            throw new DomainBadRequestException("review.approve.scan_in_progress", reviewTaskId);
        }

        int updated = reviewTaskRepository.updateStatusWithVersion(
                reviewTaskId, ReviewTaskStatus.APPROVED, reviewerId, comment, task.getVersion());
        if (updated == 0) {
            throw new ConcurrentModificationException("Review task was modified concurrently");
        }
        syncReviewTaskState(task, ReviewTaskStatus.APPROVED, reviewerId, comment);
        entityManager.detach(task);

        Skill skill = skillRepository.findById(skillVersion.getSkillId())
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillVersion.getSkillId()));

        // Publish via the shared publication service (name-conflict guard, status/pointer/metadata
        // transition, and SkillPublishedEvent) so human-review and review-exempt flows stay in lockstep.
        skillPublicationService.publishVersion(skill, skillVersion, reviewerId);

        eventPublisher.publishEvent(new ReviewApprovedEvent(
                task.getId(), skill.getId(), skillVersion.getId(),
                reviewerId, task.getSubmittedBy()));
        governanceNotificationService.notifyUser(
                task.getSubmittedBy(),
                "REVIEW",
                "REVIEW_TASK",
                reviewTaskId,
                "Review approved",
                "{\"status\":\"APPROVED\"}"
        );

        return task;
    }

    /**
     * Rejects a pending review task and returns the underlying version to a
     * non-published state with reviewer metadata captured on the task.
     */
    @Transactional
    public ReviewTask rejectReview(Long reviewTaskId, String reviewerId, String comment,
                                   Map<Long, NamespaceRole> userNamespaceRoles,
                                   Set<String> platformRoles) {
        ReviewTask task = reviewTaskRepository.findById(reviewTaskId)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found", reviewTaskId));

        if (task.getStatus() != ReviewTaskStatus.PENDING) {
            throw new DomainBadRequestException("review.not_pending", reviewTaskId);
        }

        Namespace namespace = namespaceRepository.findById(task.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", task.getNamespaceId()));
        assertNamespaceActive(namespace);

        if (!permissionChecker.canReview(task, reviewerId, namespace.getType(),
                userNamespaceRoles, platformRoles)) {
            throw new DomainForbiddenException("review.no_permission");
        }

        int updated = reviewTaskRepository.updateStatusWithVersion(
                reviewTaskId, ReviewTaskStatus.REJECTED, reviewerId, comment, task.getVersion());
        if (updated == 0) {
            throw new ConcurrentModificationException("Review task was modified concurrently");
        }
        syncReviewTaskState(task, ReviewTaskStatus.REJECTED, reviewerId, comment);
        entityManager.detach(task);

        SkillVersion skillVersion = skillVersionRepository.findById(task.getSkillVersionId())
                .orElseThrow(() -> new DomainNotFoundException("skill_version.not_found", task.getSkillVersionId()));
        Skill skill = skillRepository.findById(skillVersion.getSkillId())
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillVersion.getSkillId()));
        skillVersion.setStatus(SkillVersionStatus.REJECTED);
        skillVersionRepository.save(skillVersion);
        eventPublisher.publishEvent(new ReviewRejectedEvent(
                task.getId(), skill.getId(), skillVersion.getId(),
                reviewerId, task.getSubmittedBy(), comment));
        governanceNotificationService.notifyUser(
                task.getSubmittedBy(),
                "REVIEW",
                "REVIEW_TASK",
                reviewTaskId,
                "Review rejected",
                "{\"status\":\"REJECTED\"}"
        );

        return task;
    }

    /**
     * Withdraws a previously submitted review request and puts the version back
     * into draft so the owner can amend and resubmit it.
     */
    @Transactional
    public SkillVersion withdrawReview(Long skillVersionId, String userId) {
        ReviewTask task = reviewTaskRepository.findBySkillVersionIdAndStatus(
                        skillVersionId, ReviewTaskStatus.PENDING)
                .orElseThrow(() -> new DomainNotFoundException("review_task.not_found_for_version", skillVersionId));

        if (!task.getSubmittedBy().equals(userId)) {
            throw new DomainForbiddenException("review.withdraw.not_submitter");
        }

        reviewTaskRepository.delete(task);

        SkillVersion skillVersion = skillVersionRepository.findById(skillVersionId)
                .orElseThrow(() -> new DomainNotFoundException("skill_version.not_found", skillVersionId));
        Skill skill = skillRepository.findById(skillVersion.getSkillId())
                .orElseThrow(() -> new DomainNotFoundException("skill.not_found", skillVersion.getSkillId()));
        Namespace namespace = namespaceRepository.findById(skill.getNamespaceId())
                .orElseThrow(() -> new DomainNotFoundException("namespace.not_found", skill.getNamespaceId()));
        assertNamespaceActive(namespace);
        return skillGovernanceService.withdrawPendingVersion(skill, skillVersion, userId);
    }

    public boolean canReviewNamespace(ReviewTask task,
                                      String userId,
                                      com.iflytek.skillhub.domain.namespace.NamespaceType namespaceType,
                                      Map<Long, NamespaceRole> userNamespaceRoles,
                                      Set<String> platformRoles) {
        return permissionChecker.canReviewNamespace(task.getNamespaceId(), namespaceType, userNamespaceRoles, platformRoles);
    }

    public boolean canViewReview(ReviewTask task,
                                 String userId,
                                 com.iflytek.skillhub.domain.namespace.NamespaceType namespaceType,
                                 Map<Long, NamespaceRole> userNamespaceRoles,
                                 Set<String> platformRoles) {
        return permissionChecker.canViewReview(task, userId, namespaceType, userNamespaceRoles, platformRoles);
    }

    private void assertNamespaceActive(Namespace namespace) {
        if (namespace.getStatus() == NamespaceStatus.FROZEN) {
            throw new DomainBadRequestException("error.namespace.frozen", namespace.getSlug());
        }
        if (namespace.getStatus() == NamespaceStatus.ARCHIVED) {
            throw new DomainBadRequestException("error.namespace.archived", namespace.getSlug());
        }
    }

    private Instant currentTime() {
        return Instant.now(clock);
    }

    private void syncReviewTaskState(ReviewTask task,
                                     ReviewTaskStatus status,
                                     String reviewedBy,
                                     String comment) {
        task.setStatus(status);
        task.setReviewedBy(reviewedBy);
        task.setReviewComment(comment);
        task.setReviewedAt(currentTime());
    }
}
