package com.iflytek.skillhub.controller.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.review.ReviewTaskStatus;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillFile;
import com.iflytek.skillhub.domain.skill.SkillFileRepository;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionDeletionLock;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillGovernanceService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.storage.ObjectStorageService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillVersionDeleteFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NamespaceRepository namespaceRepository;

    @Autowired
    private SkillRepository skillRepository;

    @Autowired
    private SkillVersionRepository skillVersionRepository;

    @Autowired
    private SkillFileRepository skillFileRepository;

    @Autowired
    private ReviewTaskRepository reviewTaskRepository;

    @Autowired
    private SkillVersionDeletionLock skillVersionDeletionLock;

    @Autowired
    private SkillGovernanceService skillGovernanceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ObjectStorageService objectStorageService;

    @MockBean
    private NamespaceMemberRepository namespaceMemberRepository;

    @MockBean
    private DeviceAuthService deviceAuthService;

    @Test
    void deleteRejectedVersion_removesOnlyItsReviewHistory() throws Exception {
        String ownerId = "owner-1";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaceRepository.save(
                new Namespace("version-delete-" + suffix, "Version Delete " + suffix, ownerId)
        );

        Skill skill = new Skill(namespace.getId(), "demo-skill-" + suffix, ownerId, SkillVisibility.PUBLIC);
        skill.setCreatedBy(ownerId);
        skill.setUpdatedBy(ownerId);
        skill = skillRepository.save(skill);

        SkillVersion rejectedVersion = new SkillVersion(skill.getId(), "1.0.0", ownerId);
        rejectedVersion.setStatus(SkillVersionStatus.REJECTED);
        rejectedVersion = skillVersionRepository.save(rejectedVersion);

        SkillVersion retainedVersion = new SkillVersion(skill.getId(), "2.0.0", ownerId);
        retainedVersion.setStatus(SkillVersionStatus.REJECTED);
        retainedVersion = skillVersionRepository.save(retainedVersion);

        ReviewTask rejectedTask = new ReviewTask(rejectedVersion.getId(), namespace.getId(), ownerId);
        rejectedTask.setStatus(ReviewTaskStatus.REJECTED);
        rejectedTask = reviewTaskRepository.save(rejectedTask);

        ReviewTask approvedTask = new ReviewTask(rejectedVersion.getId(), namespace.getId(), ownerId);
        approvedTask.setStatus(ReviewTaskStatus.APPROVED);
        approvedTask = reviewTaskRepository.save(approvedTask);

        ReviewTask retainedTask = new ReviewTask(retainedVersion.getId(), namespace.getId(), ownerId);
        retainedTask.setStatus(ReviewTaskStatus.REJECTED);
        retainedTask = reviewTaskRepository.save(retainedTask);

        Long skillId = skill.getId();
        Long rejectedVersionId = rejectedVersion.getId();

        mockMvc.perform(delete("/api/web/skills/{namespace}/{slug}/versions/{version}",
                        namespace.getSlug(), skill.getSlug(), rejectedVersion.getVersion())
                        .with(authentication(portalAuth(ownerId, "USER")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.skillId").value(skillId))
                .andExpect(jsonPath("$.data.versionId").value(rejectedVersionId))
                .andExpect(jsonPath("$.data.action").value("DELETE_VERSION"))
                .andExpect(jsonPath("$.data.status").value("1.0.0"));

        assertThat(skillVersionRepository.findById(rejectedVersion.getId())).isEmpty();
        assertThat(skillVersionRepository.findById(retainedVersion.getId())).isPresent();
        assertThat(reviewTaskRepository.findById(rejectedTask.getId())).isEmpty();
        assertThat(reviewTaskRepository.findById(approvedTask.getId())).isEmpty();
        assertThat(reviewTaskRepository.findById(retainedTask.getId())).isPresent();
        verify(objectStorageService).deleteObjects(argThat(keys ->
                keys.equals(List.of("packages/" + skillId + "/" + rejectedVersionId + "/bundle.zip"))
        ));
    }

    @Test
    void concurrentDeletes_waitForCommitAndLeaveOneVersion() throws Exception {
        String ownerId = "owner-concurrent";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaceRepository.save(
                new Namespace("concurrent-delete-" + suffix, "Concurrent Delete " + suffix, ownerId)
        );
        Skill skill = new Skill(namespace.getId(), "demo-skill-" + suffix, ownerId, SkillVisibility.PUBLIC);
        skill.setCreatedBy(ownerId);
        skill.setUpdatedBy(ownerId);
        skill = skillRepository.save(skill);

        SkillVersion firstVersion = new SkillVersion(skill.getId(), "1.0.0", ownerId);
        firstVersion.setStatus(SkillVersionStatus.REJECTED);
        firstVersion = skillVersionRepository.save(firstVersion);
        SkillVersion secondVersion = new SkillVersion(skill.getId(), "2.0.0", ownerId);
        secondVersion.setStatus(SkillVersionStatus.REJECTED);
        secondVersion = skillVersionRepository.save(secondVersion);
        skillVersionRepository.flush();

        Long skillId = skill.getId();
        Long firstVersionId = firstVersion.getId();
        Long secondVersionId = secondVersion.getId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> firstDelete = null;
        Future<String> secondDelete = null;

        try {
            firstDelete = executor.submit(() -> transaction.execute(status -> {
                Skill lockedSkill = skillVersionDeletionLock.lockAndRefresh(skillId).orElseThrow();
                SkillVersion target = skillVersionRepository.findById(firstVersionId).orElseThrow();
                firstLocked.countDown();
                awaitLatch(releaseFirst);
                skillGovernanceService.deleteVersion(
                        lockedSkill, target, ownerId, Map.of(), "127.0.0.1", "JUnit", namespace.getSlug()
                );
                return "deleted";
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            secondDelete = executor.submit(() -> {
                try {
                    return transaction.execute(status -> {
                        Skill lockedSkill = skillVersionDeletionLock.lockAndRefresh(skillId).orElseThrow();
                        SkillVersion target = skillVersionRepository.findById(secondVersionId).orElseThrow();
                        skillGovernanceService.deleteVersion(
                                lockedSkill, target, ownerId, Map.of(), "127.0.0.1", "JUnit", namespace.getSlug()
                        );
                        return "deleted";
                    });
                } catch (DomainBadRequestException ex) {
                    return ex.messageCode();
                }
            });
            awaitBlockedDeletionSession();

            releaseFirst.countDown();
            assertThat(firstDelete.get(5, TimeUnit.SECONDS)).isEqualTo("deleted");
            assertThat(secondDelete.get(5, TimeUnit.SECONDS))
                    .isEqualTo("error.skill.version.delete.lastVersion");
            assertThat(skillVersionRepository.findBySkillId(skillId)).hasSize(1);
        } finally {
            releaseFirst.countDown();
            if (firstDelete != null) {
                firstDelete.cancel(true);
            }
            if (secondDelete != null) {
                secondDelete.cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleLatestPointer_doesNotOverwriteNewPrivateVersion() {
        String ownerId = "owner-stale-latest";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaceRepository.save(
                new Namespace("stale-latest-" + suffix, "Stale Latest " + suffix, ownerId)
        );
        Skill persistedSkill = new Skill(
                namespace.getId(), "private-skill-" + suffix, ownerId, SkillVisibility.PRIVATE
        );
        persistedSkill.setCreatedBy(ownerId);
        persistedSkill.setUpdatedBy(ownerId);
        persistedSkill = skillRepository.save(persistedSkill);

        SkillVersion staleTarget = new SkillVersion(persistedSkill.getId(), "1.0.0", ownerId);
        staleTarget.setStatus(SkillVersionStatus.UPLOADED);
        staleTarget = skillVersionRepository.save(staleTarget);
        SkillVersion newLatest = new SkillVersion(persistedSkill.getId(), "2.0.0", ownerId);
        newLatest.setStatus(SkillVersionStatus.UPLOADED);
        newLatest = skillVersionRepository.save(newLatest);
        persistedSkill.setLatestVersionId(newLatest.getId());
        skillRepository.save(persistedSkill);
        skillRepository.flush();

        Skill staleSkillView = new Skill(
                namespace.getId(), persistedSkill.getSlug(), ownerId, SkillVisibility.PRIVATE
        );
        ReflectionTestUtils.setField(staleSkillView, "id", persistedSkill.getId());
        staleSkillView.setLatestVersionId(staleTarget.getId());
        SkillVersion staleVersionView = new SkillVersion(persistedSkill.getId(), staleTarget.getVersion(), ownerId);
        ReflectionTestUtils.setField(staleVersionView, "id", staleTarget.getId());
        staleVersionView.setStatus(SkillVersionStatus.UPLOADED);

        skillGovernanceService.deleteVersion(
                staleSkillView,
                staleVersionView,
                ownerId,
                Map.of(),
                "127.0.0.1",
                "JUnit",
                namespace.getSlug()
        );

        assertThat(skillVersionRepository.findById(staleTarget.getId())).isEmpty();
        assertThat(skillVersionRepository.findById(newLatest.getId())).isPresent();
        assertThat(skillRepository.findById(persistedSkill.getId()))
                .get()
                .extracting(Skill::getLatestVersionId)
                .isEqualTo(newLatest.getId());
    }

    @Test
    void concurrentStatusChange_rollsBackRelatedDeletesAndSuppressesStorageCleanup() {
        String ownerId = "owner-status-race";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Namespace namespace = namespaceRepository.save(
                new Namespace("status-race-" + suffix, "Status Race " + suffix, ownerId)
        );
        Skill persistedSkill = new Skill(
                namespace.getId(), "status-race-skill-" + suffix, ownerId, SkillVisibility.PRIVATE
        );
        persistedSkill.setCreatedBy(ownerId);
        persistedSkill.setUpdatedBy(ownerId);
        persistedSkill = skillRepository.save(persistedSkill);

        SkillVersion target = new SkillVersion(persistedSkill.getId(), "1.0.0", ownerId);
        target.setStatus(SkillVersionStatus.UPLOADED);
        target = skillVersionRepository.save(target);
        SkillVersion retained = new SkillVersion(persistedSkill.getId(), "0.9.0", ownerId);
        retained.setStatus(SkillVersionStatus.PUBLISHED);
        retained = skillVersionRepository.save(retained);
        persistedSkill.setLatestVersionId(target.getId());
        skillRepository.save(persistedSkill);
        skillRepository.flush();

        SkillVersion staleVersionView = new SkillVersion(persistedSkill.getId(), target.getVersion(), ownerId);
        ReflectionTestUtils.setField(staleVersionView, "id", target.getId());
        staleVersionView.setStatus(SkillVersionStatus.UPLOADED);
        target.setStatus(SkillVersionStatus.PUBLISHED);
        skillVersionRepository.save(target);
        skillVersionRepository.flush();

        ReviewTask reviewTask = new ReviewTask(target.getId(), namespace.getId(), ownerId);
        reviewTask.setStatus(ReviewTaskStatus.REJECTED);
        reviewTask = reviewTaskRepository.save(reviewTask);
        SkillFile file = skillFileRepository.save(new SkillFile(
                target.getId(), "SKILL.md", 8L, "text/markdown", "sha", "skills/status-race/SKILL.md"
        ));

        Long skillId = persistedSkill.getId();
        Long targetId = target.getId();
        Long reviewTaskId = reviewTask.getId();
        Skill staleSkillView = persistedSkill;
        DomainBadRequestException ex = org.junit.jupiter.api.Assertions.assertThrows(
                DomainBadRequestException.class,
                () -> skillGovernanceService.deleteVersion(
                        staleSkillView,
                        staleVersionView,
                        ownerId,
                        Map.of(),
                        "127.0.0.1",
                        "JUnit",
                        namespace.getSlug()
                )
        );

        assertThat(ex.messageCode()).isEqualTo("error.skill.version.delete.concurrent");
        assertThat(skillVersionRepository.findById(targetId))
                .get()
                .extracting(SkillVersion::getStatus)
                .isEqualTo(SkillVersionStatus.PUBLISHED);
        assertThat(skillRepository.findById(skillId))
                .get()
                .extracting(Skill::getLatestVersionId)
                .isEqualTo(targetId);
        assertThat(reviewTaskRepository.findById(reviewTaskId)).isPresent();
        assertThat(skillFileRepository.findByVersionId(targetId))
                .extracting(SkillFile::getId)
                .containsExactly(file.getId());
        org.mockito.Mockito.verify(objectStorageService, org.mockito.Mockito.never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
    }

    private void awaitBlockedDeletionSession() {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            Integer blockedSessions = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.SESSIONS
                    WHERE BLOCKER_ID IS NOT NULL
                      AND EXECUTING_STATEMENT LIKE '%FROM skill%FOR UPDATE%'
                    """, Integer.class);
            if (blockedSessions != null && blockedSessions > 0) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while observing blocked deletion session", ex);
            }
        }
        throw new AssertionError("Second deletion never reached the database lock wait");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent version deletion");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating version deletion", ex);
        }
    }

    private UsernamePasswordAuthenticationToken portalAuth(String userId, String... roles) {
        PlatformPrincipal principal = new PlatformPrincipal(
                userId,
                userId,
                userId + "@example.com",
                "",
                "session",
                Set.of(roles)
        );
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }
}
