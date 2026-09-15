package com.iflytek.skillhub.controller.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.rbac.RbacService;
import com.iflytek.skillhub.domain.namespace.*;
import com.iflytek.skillhub.domain.security.*;
import com.iflytek.skillhub.domain.skill.*;
import com.iflytek.skillhub.domain.skill.service.SkillSharingService;
import com.iflytek.skillhub.domain.skill.service.SkillHardDeleteService;
import com.iflytek.skillhub.search.SearchEmbeddingService;
import com.iflytek.skillhub.storage.ObjectStorageService;
import com.iflytek.skillhub.task.SkillSharingTask;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP authorization, persistence and transactions; only the external scanner is simulated. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillSharingFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired NamespaceRepository namespaces;
    @Autowired NamespaceMemberRepository members;
    @Autowired SkillRepository skills;
    @Autowired SkillVersionRepository versions;
    @Autowired SkillFileRepository files;
    @Autowired SkillShareRequestRepository requests;
    @Autowired SecurityAuditRepository audits;
    @Autowired ObjectStorageService storage;
    @Autowired SkillSharingService sharing;
    @Autowired SkillHardDeleteService hardDelete;
    @MockBean SecurityScanService scanner;
    @MockBean RbacService rbac;
    @MockBean SearchEmbeddingService embeddings;
    @MockBean SkillSharingTask scheduler;
    private Skill skill;
    private SkillVersion selected;
    private SkillVersion history;
    private Namespace source;
    private Namespace target;
    private String author;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        author = "share-author-" + suffix;
        source = new Namespace("private-" + suffix, "Private", author);
        source.setType(NamespaceType.SYSTEM);
        source = namespaces.save(source);
        target = namespaces.save(new Namespace("team-" + suffix, "Team", "reviewer"));
        members.save(new NamespaceMember(target.getId(), author, NamespaceRole.MEMBER));
        members.save(new NamespaceMember(target.getId(), "reviewer", NamespaceRole.ADMIN));
        skill = skills.save(new Skill(source.getId(), "share-demo-" + suffix, author, SkillVisibility.PRIVATE));
        history = privateVersion("1.0.0", SkillVersionStatus.PUBLISHED);
        selected = privateVersion("1.1.0", SkillVersionStatus.UPLOADED);
        skill.setLatestVersionId(history.getId());
        skill = skills.save(skill);
        when(scanner.isEnabled()).thenReturn(true);
        when(scanner.triggerSharingScan(anyLong(), anyList(), eq(author))).thenAnswer(call -> {
            SecurityAudit audit = new SecurityAudit(call.getArgument(0), ScannerType.SKILL_SCANNER);
            audit.setVerdict(SecurityVerdict.SAFE);
            audit.setIsSafe(true);
            audit.setScannedAt(Instant.now());
            return audits.save(audit);
        });
        when(rbac.getUserRoleCodes(anyString())).thenReturn(Set.of());
        when(embeddings.embed(anyString())).thenReturn("");
    }

    @Test
    void deletingDetachedSkillUsesUpdatedOptimisticRevision() {
        hardDelete.hardDeleteSkill(skill, source.getSlug(), author, "127.0.0.1", "JUnit");
        assertThat(skills.findById(skill.getId())).isEmpty();
        assertThat(versions.findBySkillId(skill.getId())).isEmpty();
    }

    @Test
    void approvalKeepsIdentityAndPrivateHistoryAcrossAllReadRoutes() throws Exception {
        long requestId = submit("one");
        SkillShareRequest pending = sharing.advance(requestId);
        assertThat(pending.getStatus()).isEqualTo(SkillShareStatus.PENDING_REVIEW);
        mvc.perform(get("/api/v1/reviews/" + pending.getReviewTaskId() + "/skill-detail").with(auth("reviewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.length()").value(1))
                .andExpect(jsonPath("$.data.versions[0].id").value(selected.getId()));
        mvc.perform(post("/api/v1/reviews/" + pending.getReviewTaskId() + "/approve")
                        .with(auth("reviewer")).with(csrf()).contentType("application/json").content("{\"comment\":\"ready\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));

        Skill saved = skills.findById(skill.getId()).orElseThrow();
        assertThat(saved.getNamespaceId()).isEqualTo(target.getId());
        assertThat(saved.getLatestVersionId()).isEqualTo(selected.getId());
        assertThat(saved.getPrivateSourceNamespaceId()).isEqualTo(source.getId());
        assertThat(versions.findById(history.getId()).orElseThrow().getDistributionVisibility()).isEqualTo(SkillVisibility.PRIVATE);
        assertThat(versions.findById(selected.getId()).orElseThrow().getDistributionVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(SkillShareStatus.COMPLETED);

        String base = "/api/v1/skills/" + target.getSlug() + "/" + skill.getSlug();
        mvc.perform(get(base + "/versions").with(auth("reviewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get(base + "/versions").with(auth(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        for (String path : List.of("/versions/1.0.0", "/versions/1.0.0/files", "/versions/1.0.0/file?path=SKILL.md", "/versions/compare?from=1.0.0&to=1.1.0")) {
            mvc.perform(get(base + path).with(auth("reviewer"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").isEmpty());
        }
        mvc.perform(get("/api/v1/skills/" + skill.getId() + "/versions/" + history.getId() + "/security-audit").with(auth("reviewer")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/skills/by-id/" + skill.getId() + "/location").with(auth("reviewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.namespace").value(target.getSlug()));
        mvc.perform(get("/api/v1/skills/" + source.getSlug() + "/" + skill.getSlug()).with(auth(author)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(skill.getId()));
    }

    @Test
    void duplicateSubmissionAndWithdrawalDoNotCreateCopiesOrChangeVersionStatus() throws Exception {
        long first = submit("retry-key");
        assertThat(submit("retry-key")).isEqualTo(first);
        mvc.perform(post(base() + "/" + first + "/withdraw").with(auth(author)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        sharing.advance(first);
        assertThat(skills.findById(skill.getId()).orElseThrow().getNamespaceId()).isEqualTo(source.getId());
        assertThat(versions.findById(selected.getId()).orElseThrow().getStatus()).isEqualTo(SkillVersionStatus.UPLOADED);
        assertThat(skills.findByNamespaceIdAndSlug(target.getId(), skill.getSlug())).isEmpty();
        assertThat(requests.findById(first).orElseThrow().getStatus()).isEqualTo(SkillShareStatus.WITHDRAWN);
    }

    @Test
    void nameClaimBeforeApprovalPreservesPrivateSkillAndPendingRequest() throws Exception {
        long requestId = submit("collision");
        SkillShareRequest pending = sharing.advance(requestId);
        skills.save(new Skill(target.getId(), skill.getSlug(), "other", SkillVisibility.NAMESPACE_ONLY));
        mvc.perform(post("/api/v1/reviews/" + pending.getReviewTaskId() + "/approve")
                        .with(auth("reviewer")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(skills.findById(skill.getId()).orElseThrow().getNamespaceId()).isEqualTo(source.getId());
        assertThat(versions.findById(selected.getId()).orElseThrow().getDistributionVisibility()).isEqualTo(SkillVisibility.PRIVATE);
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(SkillShareStatus.PENDING_REVIEW);
        sharing.advance(requestId);
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(SkillShareStatus.FAILED);
    }

    @Test
    void ownerOnlyAndCsrfGuardsProtectSharingMutations() throws Exception {
        mvc.perform(get(base()).with(auth("reviewer"))).andExpect(status().isForbidden());
        mvc.perform(post(base().replace("/api/v1/", "/api/web/")).with(auth(author)).contentType("application/json").content(command("csrf")))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(base()).with(auth("reviewer")).with(csrf()).contentType("application/json").content(command("other")))
                .andExpect(status().isForbidden());
    }

    private SkillVersion privateVersion(String number, SkillVersionStatus status) throws Exception {
        SkillVersion version = new SkillVersion(skill.getId(), number, author);
        version.setStatus(status);
        version.setDistributionVisibility(SkillVisibility.PRIVATE);
        version.setRequestedVisibility(SkillVisibility.PRIVATE);
        version.setBundleReady(true);
        version.setDownloadReady(true);
        version.setFileCount(1);
        version = versions.save(version);
        byte[] content = ("---\nname: " + skill.getSlug() + "\ndescription: A tested private skill\nversion: " + number + "\n---\n# Usage\nUse this skill to format local example text.\n").getBytes(StandardCharsets.UTF_8);
        String key = "sharing-tests/" + version.getId() + "/SKILL.md";
        storage.putObject(key, new ByteArrayInputStream(content), content.length, "text/markdown");
        files.saveAll(List.of(new SkillFile(version.getId(), "SKILL.md", (long) content.length, "text/markdown",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)), key)));
        return version;
    }
    private String base() { return "/api/v1/skills/by-id/" + skill.getId() + "/sharing"; }
    private String command(String key) throws Exception { return json.writeValueAsString(Map.of("versionId", selected.getId(), "targetNamespaceId", target.getId(), "targetVisibility", "NAMESPACE_ONLY", "idempotencyKey", key, "confirmWarnings", true)); }
    private long submit(String key) throws Exception {
        String response = mvc.perform(post(base()).with(auth(author)).with(csrf()).contentType("application/json").content(command(key)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SCANNING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("data").path("id").asLong();
    }
    private RequestPostProcessor auth(String user) {
        PlatformPrincipal principal = new PlatformPrincipal(user, user, "test@example.test", null, "test", Set.of());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
