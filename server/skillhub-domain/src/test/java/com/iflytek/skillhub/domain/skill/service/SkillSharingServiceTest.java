package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.audit.AuditLogService;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.review.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;
import com.iflytek.skillhub.domain.skill.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the private-to-shared transition with the real publication and review permission rules. */
class SkillSharingServiceTest {
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillVersionRepository versions = mock(SkillVersionRepository.class);
    private final SkillShareRequestRepository requests = mock(SkillShareRequestRepository.class);
    private final NamespaceRepository namespaces = mock(NamespaceRepository.class);
    private final NamespaceMemberRepository members = mock(NamespaceMemberRepository.class);
    private final NamespacePublishLock namespaceLock = mock(NamespacePublishLock.class);
    private final SkillVersionDeletionLock skillLock = mock(SkillVersionDeletionLock.class);
    private final SecurityAuditRepository audits = mock(SecurityAuditRepository.class);
    private final ReviewTaskRepository reviews = mock(ReviewTaskRepository.class);
    private final SharingActorPrivileges privileges = mock(SharingActorPrivileges.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private SkillSharingService service;
    private Skill skill;
    private SkillVersion selected;
    private SkillVersion history;
    private Namespace target;
    private SecurityAudit safeAudit;
    private ReviewTask review;

    @BeforeEach
    void setUp() {
        SkillPublicationService publication = new SkillPublicationService(skills, versions, events, new ObjectMapper(), clock, namespaceLock);
        service = new SkillSharingService(skills, versions, requests, namespaces, members, namespaceLock,
                skillLock, audits, reviews, new ReviewPermissionChecker(), privileges, publication, events,
                mock(AuditLogService.class), clock);
        skill = id(new Skill(1L, "demo", "author", SkillVisibility.PRIVATE), 10L);
        skill.setLatestVersionId(20L);
        skill.setSummary("Private description");
        selected = privateVersion(21L, "1.1.0", SkillVersionStatus.UPLOADED);
        selected.setParsedMetadataJson("{\"name\":\"Demo\",\"description\":\"Shared description\",\"version\":\"1.1.0\",\"body\":\"Body\",\"frontmatter\":{}}");
        history = privateVersion(20L, "1.0.0", SkillVersionStatus.PUBLISHED);
        Namespace source = id(new Namespace("private", "Private", "system"), 1L);
        source.setType(NamespaceType.SYSTEM);
        target = id(new Namespace("team", "Team", "reviewer"), 2L);
        when(skills.findById(10L)).thenReturn(Optional.of(skill));
        when(skillLock.lockAndRefresh(10L)).thenReturn(Optional.of(skill));
        when(versions.findBySkillId(10L)).thenReturn(List.of(history, selected));
        when(versions.findById(21L)).thenReturn(Optional.of(selected));
        when(versions.findById(20L)).thenReturn(Optional.of(history));
        when(namespaces.findById(1L)).thenReturn(Optional.of(source));
        when(namespaces.findById(2L)).thenReturn(Optional.of(target));
        when(members.findByNamespaceIdAndUserId(2L, "author")).thenReturn(Optional.of(new NamespaceMember(2L, "author", NamespaceRole.MEMBER)));
        when(skills.findByNamespaceIdAndSlug(2L, "demo")).thenReturn(List.of());
        when(requests.save(any())).thenAnswer(call -> {
            SkillShareRequest request = call.getArgument(0);
            if (request.getId() == null) id(request, 100L);
            when(requests.findById(request.getId())).thenReturn(Optional.of(request));
            when(requests.findFirstBySkillIdOrderByIdDesc(10L)).thenReturn(Optional.of(request));
            when(requests.findBySkillIdAndIdempotencyKey(10L, request.getIdempotencyKey())).thenReturn(Optional.of(request));
            return request;
        });
        when(reviews.save(any())).thenAnswer(call -> {
            review = id(call.getArgument(0), 200L);
            when(reviews.findById(200L)).thenReturn(Optional.of(review));
            return review;
        });
        when(reviews.updateStatusWithVersion(anyLong(), any(), anyString(), any(), anyInt())).thenReturn(1);
        safeAudit = id(new SecurityAudit(21L, ScannerType.SKILL_SCANNER), 300L);
        safeAudit.setVerdict(SecurityVerdict.SAFE);
        safeAudit.setScannedAt(now);
        when(audits.findById(300L)).thenReturn(Optional.of(safeAudit));
    }

    @Test
    void submittingFreezesOnlySelectedVersionAndPreservesPrivateUsage() {
        SkillShareRequest request = submit();
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.SCANNING);
        assertThat(selected.getSharingRequestId()).isEqualTo(100L);
        assertOriginalUnchanged();
        assertThatThrownBy(selected::assertNotSharing).isInstanceOf(LocalizedDomainException.class);
        history.assertNotSharing();
        verify(reviews, never()).save(any());
    }

    @Test
    void repeatedSubmissionReturnsSameRequestWithoutCreatingAnother() {
        SkillShareRequest first = submit();
        assertThat(submit()).isSameAs(first);
        verify(requests, times(1)).save(any());
        assertThatThrownBy(() -> service.submit(10L, 3L, "author", "one"))
                .isInstanceOf(LocalizedDomainException.class);
    }

    @Test
    void anotherSubmissionIsBlockedWhileActive() {
        submit();
        assertThatThrownBy(() -> service.submit(10L, 2L, "author", "two"))
                .isInstanceOf(LocalizedDomainException.class);
    }

    @Test
    void namespaceAdminCannotShareAnotherAuthorsPrivateVersion() {
        assertThatThrownBy(() -> service.submit(10L, 2L, "reviewer", "one"))
                .isInstanceOf(LocalizedDomainException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void moveAlwaysUsesMemberVisibilityEvenForGlobalSpace() {
        target.setType(NamespaceType.GLOBAL);
        assertThat(submit().getTargetVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThatThrownBy(() -> service.validateTarget(skill, 21L, 2L, SkillVisibility.PUBLIC, "author"))
                .isInstanceOf(LocalizedDomainException.class);
    }

    @Test
    void sharedSkillUsesPublishedVersionAndWithdrawsUnfinishedUpdatesOnlyOnCompletion() {
        skill.setVisibility(SkillVisibility.PUBLIC);
        history.setDistributionVisibility(SkillVisibility.PUBLIC);
        selected.setStatus(SkillVersionStatus.PENDING_REVIEW);
        selected.setDistributionVisibility(SkillVisibility.PUBLIC);
        ReviewTask oldReview = id(new ReviewTask(21L, 1L, "author"), 201L);
        when(reviews.findBySkillVersionIdAndStatus(21L, ReviewTaskStatus.PENDING)).thenReturn(Optional.of(oldReview));
        SkillVersion scanning = privateVersion(22L, "1.2.0", SkillVersionStatus.SCANNING);
        scanning.setDistributionVisibility(SkillVisibility.PUBLIC);
        scanning.setAutoPublishOnScanPass(true);
        when(versions.findBySkillId(10L)).thenReturn(List.of(history, selected, scanning));
        safeAudit = id(new SecurityAudit(20L, ScannerType.SKILL_SCANNER), 301L);
        safeAudit.setVerdict(SecurityVerdict.SAFE);
        safeAudit.setScannedAt(now);
        when(audits.findById(301L)).thenReturn(Optional.of(safeAudit));
        SkillShareRequest request = submit();
        request.setSecurityAuditId(301L);
        assertThat(request.getSkillVersionId()).isEqualTo(20L);
        assertThat(history.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
        assertThat(skill.getNamespaceId()).isEqualTo(1L);
        assertThat(selected.getStatus()).isEqualTo(SkillVersionStatus.PENDING_REVIEW);
        service.advance(100L);
        service.decide(review, "reviewer", "Ready", Map.of(2L, NamespaceRole.ADMIN), Set.of(), true);
        assertThat(skill.getNamespaceId()).isEqualTo(2L);
        assertThat(skill.getLatestVersionId()).isEqualTo(20L);
        assertThat(skill.getVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThat(selected.getStatus()).isEqualTo(SkillVersionStatus.UPLOADED);
        assertThat(scanning.getStatus()).isEqualTo(SkillVersionStatus.UPLOADED);
        assertThat(scanning.isAutoPublishOnScanPass()).isFalse();
        verify(reviews).delete(oldReview);
        assertThat(history.getDistributionVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThat(selected.getRequestedVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
    }

    @Test
    void newerUnavailablePrivateVersionDoesNotReplaceLatestAvailable() {
        SkillVersion scanning = privateVersion(22L, "2.0.0", SkillVersionStatus.SCANNING);
        when(versions.findBySkillId(10L)).thenReturn(List.of(history, selected, scanning));
        assertThat(submit().getSkillVersionId()).isEqualTo(selected.getId());
    }

    @Test
    void sameSpaceAndPrivateSpaceTargetsAreRejected() {
        assertThatThrownBy(() -> service.validateTarget(skill, 21L, 1L, SkillVisibility.NAMESPACE_ONLY, "author"))
                .isInstanceOf(LocalizedDomainException.class);
        skill.shareToNamespace(2L);
        assertThatThrownBy(this::submit).isInstanceOf(LocalizedDomainException.class);
    }

    @Test
    void newerPublishedVersionDuringReviewFailsMoveWithoutRollingBackLatest() {
        skill.setVisibility(SkillVisibility.NAMESPACE_ONLY);
        skill.setLatestVersionId(21L);
        selected.setStatus(SkillVersionStatus.PUBLISHED);
        selected.setDistributionVisibility(SkillVisibility.NAMESPACE_ONLY);
        SkillShareRequest request = scannedRequest();
        history.setDistributionVisibility(SkillVisibility.NAMESPACE_ONLY);
        skill.setLatestVersionId(20L);
        service.advance(100L);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.FAILED);
        assertThat(skill.getLatestVersionId()).isEqualTo(20L);
        assertThat(skill.getNamespaceId()).isEqualTo(1L);
    }

    @Test
    void unpublishedNameCollisionAlsoBlocksSharingWithoutOverwriting() {
        Skill conflict = id(new Skill(2L, "demo", "other", SkillVisibility.NAMESPACE_ONLY), 11L);
        when(skills.findByNamespaceIdAndSlug(2L, "demo")).thenReturn(List.of(conflict));
        assertThatThrownBy(this::submit).isInstanceOf(LocalizedDomainException.class);
        verify(skills, never()).save(any());
    }

    @Test
    void safeScanQueuesTargetReviewWithoutPublishing() {
        scannedRequest();
        service.advance(100L);
        assertThat(review.getNamespaceId()).isEqualTo(2L);
        assertThat(review.getShareRequestId()).isEqualTo(100L);
        assertOriginalUnchanged();
        service.advance(100L);
        verify(reviews, times(1)).save(any());
    }

    @Test
    void approvalMovesSameSkillAndOnlySelectedVersionBecomesShared() {
        SkillShareRequest request = scannedRequest();
        service.advance(100L);
        service.decide(review, "reviewer", "Ready", Map.of(2L, NamespaceRole.ADMIN), Set.of(), true);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.COMPLETED);
        assertThat(skill.getId()).isEqualTo(10L);
        assertThat(skill.getNamespaceId()).isEqualTo(2L);
        assertThat(skill.getPrivateSourceNamespaceId()).isEqualTo(1L);
        assertThat(skill.getLatestVersionId()).isEqualTo(21L);
        assertThat(skill.getSummary()).isEqualTo("Shared description");
        assertThat(selected.getVersion()).isEqualTo("1.1.0");
        assertThat(selected.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
        assertThat(selected.getDistributionVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThat(selected.getSharingRequestId()).isNull();
        assertThat(history.getDistributionVisibility()).isEqualTo(SkillVisibility.PRIVATE);
        assertThat(VersionAccessPolicy.canRead(skill, history, "reviewer", Map.of(2L, NamespaceRole.ADMIN))).isFalse();
        assertThat(VersionAccessPolicy.canRead(skill, selected, "member", Map.of(2L, NamespaceRole.MEMBER))).isTrue();
        verify(skills).save(same(skill));
    }

    @Test
    void superAdminStillRequiresSafeScanBeforeAutomaticSharing() {
        when(privileges.isSuperAdmin("author")).thenReturn(true);
        SkillShareRequest request = submit();
        service.advance(100L);
        assertOriginalUnchanged();
        request.setSecurityAuditId(300L);
        service.advance(100L);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.COMPLETED);
        verify(reviews, never()).save(any());
    }

    @Test
    void unsafeScanFailsClosedAndReleasesVersion() {
        safeAudit.setVerdict(SecurityVerdict.SUSPICIOUS);
        SkillShareRequest request = scannedRequest();
        service.advance(100L);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.FAILED);
        assertThat(selected.getSharingRequestId()).isNull();
        assertOriginalUnchanged();
    }

    @Test
    void losingTargetMembershipCancelsPendingReview() {
        SkillShareRequest request = scannedRequest();
        service.advance(100L);
        when(members.findByNamespaceIdAndUserId(2L, "author")).thenReturn(Optional.empty());
        service.advance(100L);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.FAILED);
        verify(reviews).delete(review);
        assertOriginalUnchanged();
    }

    @Test
    void withdrawnRequestIgnoresLateScanResultAndCannotBeApproved() {
        SkillShareRequest request = scannedRequest();
        service.advance(100L);
        service.withdraw(10L, 100L, "author");
        service.advance(100L);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.WITHDRAWN);
        assertThatThrownBy(() -> service.decide(review, "reviewer", "Ready", Map.of(2L, NamespaceRole.ADMIN), Set.of(), true))
                .isInstanceOf(LocalizedDomainException.class);
        assertOriginalUnchanged();
    }

    @Test
    void rejectionLeavesPackageAndPrivatePublishedHistoryIntact() {
        SkillShareRequest request = scannedRequest();
        service.advance(100L);
        service.decide(review, "reviewer", "Fix docs", Map.of(2L, NamespaceRole.ADMIN), Set.of(), false);
        assertThat(request.getStatus()).isEqualTo(SkillShareStatus.REJECTED);
        assertThat(selected.getSharingRequestId()).isNull();
        assertOriginalUnchanged();
    }

    private SkillShareRequest submit() { return service.submit(10L, 2L, "author", "one"); }
    private SkillShareRequest scannedRequest() { SkillShareRequest request = submit(); request.setSecurityAuditId(300L); return request; }
    private void assertOriginalUnchanged() {
        assertThat(skill.getNamespaceId()).isEqualTo(1L);
        assertThat(skill.getVisibility()).isEqualTo(SkillVisibility.PRIVATE);
        assertThat(skill.getLatestVersionId()).isEqualTo(20L);
        assertThat(selected.getStatus()).isEqualTo(SkillVersionStatus.UPLOADED);
        assertThat(history.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
    }
    private SkillVersion privateVersion(Long id, String version, SkillVersionStatus status) {
        SkillVersion result = id(new SkillVersion(10L, version, "author"), id);
        result.setDistributionVisibility(SkillVisibility.PRIVATE);
        result.setStatus(status);
        result.setBundleReady(true);
        result.setDownloadReady(true);
        return result;
    }
    private static <T> T id(T entity, Long id) {
        try { var field = entity.getClass().getDeclaredField("id"); field.setAccessible(true); field.set(entity, id); return entity; }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
}
