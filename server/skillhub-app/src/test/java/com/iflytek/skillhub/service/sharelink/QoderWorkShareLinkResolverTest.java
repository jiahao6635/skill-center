package com.iflytek.skillhub.service.sharelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.SkillShareLinkProperties;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QoderWorkShareLinkResolverTest {

    @Mock
    private RemoteSkillPackageDownloader downloader;

    private QoderWorkShareLinkResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new QoderWorkShareLinkResolver(
                new SkillShareLinkProperties(),
                downloader,
                new ObjectMapper()
        );
    }

    @Test
    void supportsQoderWorkShareLandingPages() {
        assertThat(resolver.supports(URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a")))
                .isTrue();
        assertThat(resolver.supports(URI.create(
                "https://www.qoder.com.cn/link/qoder-work/skill/install?share_id=abcDEF12")))
                .isTrue();
        assertThat(resolver.supports(URI.create("https://example.com/skill?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a")))
                .isFalse();
        assertThat(resolver.supports(URI.create("https://qoder.com/link/qoder-work/skill/install")))
                .isFalse();
    }

    @Test
    void extractsShareIdFromQueryAndIgnoresFragment() {
        Optional<String> shareId = resolver.extractShareId(URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a#v1"));

        assertThat(shareId).contains("934bad14bd74f1477c5d1cdd0d6a2d2a");
    }

    @Test
    void downloadsZipFromResolvedDownloadUrl() {
        URI shareUri = URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a");
        given(downloader.getJson(any(), any(), eq(Optional.empty())))
                .willReturn("{\"skill_name\":\"demo\",\"download_url\":\"https://oss.aliyuncs.com/skills/demo.zip\"}");
        given(downloader.downloadZip(eq(URI.create("https://oss.aliyuncs.com/skills/demo.zip")), any()))
                .willReturn(new byte[] {0x50, 0x4b, 0x03, 0x04});

        byte[] zip = resolver.downloadPackage(shareUri);

        assertThat(zip).containsExactly(0x50, 0x4b, 0x03, 0x04);
        verify(downloader).getJson(
                eq(URI.create("https://openapi.qoder.com.cn/api/v1/skill-links?share_id=934bad14bd74f1477c5d1cdd0d6a2d2a")),
                any(Set.class),
                eq(Optional.empty())
        );
    }

    @Test
    void unwrapsNestedDownloadUrlPayload() {
        URI shareUri = URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a");
        given(downloader.getJson(any(), any(), eq(Optional.empty())))
                .willReturn("{\"code\":0,\"data\":{\"downloadUrl\":\"https://cdn.qoder.com/a.zip\"}}");
        given(downloader.downloadZip(eq(URI.create("https://cdn.qoder.com/a.zip")), any()))
                .willReturn(new byte[] {1, 2, 3});

        assertThat(resolver.downloadPackage(shareUri)).containsExactly(1, 2, 3);
    }

    @Test
    void stopsOnAuthenticationFailureWithoutTryingLaterApiBases() {
        URI shareUri = URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a");
        given(downloader.getJson(any(), any(), eq(Optional.empty())))
                .willThrow(new DomainBadRequestException("error.skill.publish.shareLink.authRequired"));

        assertThatThrownBy(() -> resolver.downloadPackage(shareUri))
                .isInstanceOf(DomainBadRequestException.class)
                .extracting(ex -> ((DomainBadRequestException) ex).messageCode())
                .isEqualTo("error.skill.publish.shareLink.authRequired");
        verify(downloader).getJson(any(), any(), eq(Optional.empty()));
    }

    @Test
    void failsWhenMetadataOmitsDownloadUrl() {
        URI shareUri = URI.create(
                "https://qoder.com/link/qoder-work/skill/install?shareId=934bad14bd74f1477c5d1cdd0d6a2d2a");
        given(downloader.getJson(any(), any(), eq(Optional.empty())))
                .willReturn("{\"skill_name\":\"demo\"}");

        assertThatThrownBy(() -> resolver.downloadPackage(shareUri))
                .isInstanceOf(DomainBadRequestException.class)
                .extracting(ex -> ((DomainBadRequestException) ex).messageCode())
                .isEqualTo("error.skill.publish.shareLink.missingDownloadUrl");
    }
}
