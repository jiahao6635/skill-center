package com.iflytek.skillhub.service.sharelink;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.config.SkillPublishProperties;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.dto.PublishFromUrlRequest;
import com.iflytek.skillhub.dto.PublishResponse;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SkillShareLinkPublishServiceTest {

    @Mock
    private QoderWorkShareLinkResolver resolver;
    @Mock
    private SkillPublishService skillPublishService;
    @Mock
    private SkillHubMetrics skillHubMetrics;

    private SkillShareLinkPublishService service;

    @BeforeEach
    void setUp() {
        service = new SkillShareLinkPublishService(
                resolver,
                new SkillPackageArchiveExtractor(new SkillPublishProperties()),
                skillPublishService,
                skillHubMetrics
        );
    }

    @Test
    void publishesResolvedZipThroughExistingPipeline() throws Exception {
        URI shareUri = URI.create("https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a");
        given(resolver.supports(shareUri)).willReturn(true);
        given(resolver.downloadPackage(shareUri)).willReturn(buildZipBytes());

        SkillVersion version = new SkillVersion(12L, "1.0.0", "usr_1");
        version.setStatus(SkillVersionStatus.PENDING_REVIEW);
        version.setFileCount(1);
        version.setTotalSize(128L);
        ReflectionTestUtils.setField(version, "id", 34L);
        given(skillPublishService.publishFromEntries(
                eq("global"),
                ArgumentMatchers.<List<PackageEntry>>any(),
                eq("usr_1"),
                eq(SkillVisibility.PUBLIC),
                eq(Set.of("SUPER_ADMIN")),
                eq(false)))
                .willReturn(new SkillPublishService.PublishResult(12L, "demo-skill", version));

        PublishResponse response = service.publish(
                "global",
                new PublishFromUrlRequest(shareUri.toString(), "PUBLIC", false),
                principal()
        );

        assertThat(response.slug()).isEqualTo("demo-skill");
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        verify(skillHubMetrics).incrementSkillPublish("global", "PENDING_REVIEW");
    }

    @Test
    void rejectsUnsupportedUrls() {
        URI shareUri = URI.create("https://example.com/skill.zip");
        given(resolver.supports(shareUri)).willReturn(false);

        assertThatThrownBy(() -> service.publish(
                "global",
                new PublishFromUrlRequest(shareUri.toString(), "PUBLIC", false),
                principal()))
                .isInstanceOf(DomainBadRequestException.class)
                .extracting(ex -> ((DomainBadRequestException) ex).messageCode())
                .isEqualTo("error.skill.publish.shareLink.unsupported");
    }

    private static PlatformPrincipal principal() {
        return new PlatformPrincipal(
                "usr_1",
                "publisher",
                "publisher@example.com",
                "",
                "local",
                Set.of("SUPER_ADMIN")
        );
    }

    private static byte[] buildZipBytes() throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write("""
                ---
                name: Demo Skill
                version: 1.0.0
                ---
                """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }
}
