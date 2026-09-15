package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillSharingService;
import com.iflytek.skillhub.dto.*;
import java.util.Objects;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Assembles owner-only sharing controls from skill, version, namespace and review projections. */
@Repository
@Transactional(readOnly = true)
public class SkillSharingQueryRepository {
    private final SkillSharingService sharing;
    private final SkillVersionRepository versions;
    private final NamespaceRepository namespaces;
    private final NamespaceMemberRepository members;
    private final SharingActorPrivileges privileges;
    private final SkillShareRequestRepository requests;
    private final ReviewTaskRepository reviews;

    public SkillSharingQueryRepository(SkillSharingService sharing, SkillVersionRepository versions,
            NamespaceRepository namespaces, NamespaceMemberRepository members, SharingActorPrivileges privileges,
            SkillShareRequestRepository requests, ReviewTaskRepository reviews) {
        this.sharing = sharing; this.versions = versions; this.namespaces = namespaces; this.members = members;
        this.privileges = privileges; this.requests = requests; this.reviews = reviews;
    }

    public SkillSharingSettingsResponse settings(Long skillId, String actor) {
        Skill skill = sharing.requireOwner(skillId, actor);
        Namespace source = namespaces.findById(skill.getNamespaceId()).orElseThrow();
        boolean superAdmin = privileges.isSuperAdmin(actor);
        var candidates = sharing.latestAvailableVersion(skill).stream()
                .map(v -> new SkillSharingSettingsResponse.VersionOption(v.getId(), v.getVersion(), v.getStatus().name(), v.getCreatedAt(), v.getFileCount()))
                .toList();
        var targets = namespaces.findAll().stream()
                .filter(n -> n.getStatus() == NamespaceStatus.ACTIVE && n.getType() != NamespaceType.SYSTEM)
                .filter(n -> !Objects.equals(n.getId(), skill.getNamespaceId()))
                .filter(n -> superAdmin || members.findByNamespaceIdAndUserId(n.getId(), actor).isPresent())
                .map(n -> new SkillSharingSettingsResponse.TargetOption(n.getId(), n.getSlug(), n.getDisplayName(), n.getType().name()))
                .toList();
        String sharedVersion = skill.getVisibility() == SkillVisibility.PRIVATE || skill.getLatestVersionId() == null ? null
                : versions.findById(skill.getLatestVersionId()).map(SkillVersion::getVersion).orElse(null);
        return new SkillSharingSettingsResponse(skillId, skill.getSlug(), source.getSlug(), skill.getVisibility().name(),
                sharedVersion, candidates, targets, requests.findFirstBySkillIdOrderByIdDesc(skillId).map(this::response).orElse(null));
    }

    public SkillShareResponse response(SkillShareRequest request) {
        Namespace target = namespaces.findById(request.getTargetNamespaceId()).orElseThrow();
        String version = versions.findById(request.getSkillVersionId()).map(SkillVersion::getVersion).orElse(null);
        String comment = request.getReviewTaskId() == null ? null : reviews.findById(request.getReviewTaskId())
                .map(ReviewTask::getReviewComment).orElse(null);
        return new SkillShareResponse(request.getId(), request.getStatus().name(), request.getSkillVersionId(), version,
                target.getId(), target.getSlug(), target.getDisplayName(), request.getTargetVisibility().name(),
                request.getReviewTaskId(), comment, request.getErrorCode(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
