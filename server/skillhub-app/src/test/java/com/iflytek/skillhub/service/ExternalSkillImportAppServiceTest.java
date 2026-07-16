package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.controller.support.SkillPackageArchiveExtractor;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.shared.exception.DomainForbiddenException;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadataParser;
import com.iflytek.skillhub.domain.skill.origin.SkillOrigin;
import com.iflytek.skillhub.domain.skill.origin.SkillOriginRepository;
import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenanceRepository;
import com.iflytek.skillhub.domain.skill.service.SkillPublishService;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import com.iflytek.skillhub.domain.skill.validation.PrePublishValidator;
import com.iflytek.skillhub.domain.skill.validation.SkillPackageValidator;
import com.iflytek.skillhub.domain.skill.validation.ValidationResult;
import com.iflytek.skillhub.dto.external.ExternalSkillDetail;
import com.iflytek.skillhub.dto.external.ExternalSkillFile;
import com.iflytek.skillhub.dto.external.ExternalSkillImportRequest;
import com.iflytek.skillhub.dto.external.ExternalSkillSummary;
import com.iflytek.skillhub.dto.external.ExternalSkillVersion;
import com.iflytek.skillhub.external.skillhubcn.SkillHubCnClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalSkillImportAppServiceTest {
    @Mock SkillHubCnClient client;
    @Mock SkillPackageArchiveExtractor archiveExtractor;
    @Mock SkillPackageValidator packageValidator;
    @Mock SkillMetadataParser metadataParser;
    @Mock NamespaceRepository namespaceRepository;
    @Mock SkillRepository skillRepository;
    @Mock SkillOriginRepository originRepository;
    @Mock SkillVersionProvenanceRepository provenanceRepository;
    @Mock SkillPublishService publishService;
    @Mock PrePublishValidator prePublishValidator;

    private ExternalSkillImportAppService service;
    private final PackageEntry skillMd = new PackageEntry(
            "SKILL.md",
            "---\nname: demo\ndescription: Demo\nversion: 1.0.0\n---\nBody"
                    .getBytes(StandardCharsets.UTF_8),
            61,
            "text/markdown");

    @BeforeEach
    void setUp() throws Exception {
        service = new ExternalSkillImportAppService(
                client, archiveExtractor, packageValidator, metadataParser, namespaceRepository,
                skillRepository, originRepository, provenanceRepository, publishService,
                new ObjectMapper(), prePublishValidator);
        Namespace namespace = new Namespace("team-a", "Team A", "owner");
        ReflectionTestUtils.setField(namespace, "id", 11L);
        when(namespaceRepository.findBySlug("team-a")).thenReturn(Optional.of(namespace));
        when(archiveExtractor.extract(any(byte[].class))).thenReturn(List.of(skillMd));
        when(packageValidator.validate(any())).thenReturn(ValidationResult.pass());
        when(prePublishValidator.validate(any())).thenReturn(ValidationResult.pass());
        when(metadataParser.parse(any())).thenReturn(
                new SkillMetadata("demo", "Demo", "1.0.0", "Body", Map.of("license", "MIT")));
        when(client.filesFresh("demo", "1.0.0")).thenReturn(
                List.of(new ExternalSkillFile("SKILL.md", skillMd.content().length, sha(skillMd.content()))));
        when(client.declaredLicenseFresh("demo")).thenReturn(null);
        when(skillRepository.findByNamespaceIdAndSlug(11L, "demo")).thenReturn(List.of());
    }

    @Test
    void importRejectsContentChangedBetweenValidationAndPublishDownload() {
        when(client.download("demo", "1.0.0"))
                .thenReturn("archive-a".getBytes(StandardCharsets.UTF_8))
                .thenReturn("archive-b".getBytes(StandardCharsets.UTF_8));
        ExternalSkillImportRequest request = new ExternalSkillImportRequest(
                "team-a", null, sha("archive-a".getBytes(StandardCharsets.UTF_8)),
                shaWarnings("archive-a".getBytes(StandardCharsets.UTF_8)), false, false);

        assertThatThrownBy(() -> service.importVersion(
                "skillhub-cn", "demo", "1.0.0", request, "user-1", Set.of(), Map.of()))
                .isInstanceOf(DomainBadRequestException.class);

        verify(publishService, never()).publishFromEntries(
                any(), any(), any(), any(), any(), eq(true), any());
    }

    @Test
    void importDefaultsToPrivateAndReportsUploadedWithoutReviewWait() {
        byte[] archive = "archive-a".getBytes(StandardCharsets.UTF_8);
        when(client.download("demo", "1.0.0")).thenReturn(archive);
        SkillVersion version = new SkillVersion(21L, "1.0.0", "user-1");
        ReflectionTestUtils.setField(version, "id", 31L);
        version.setStatus(SkillVersionStatus.UPLOADED);
        when(publishService.publishFromEntries(
                eq("team-a"), any(), eq("user-1"), eq(SkillVisibility.PRIVATE), eq(Set.of()),
                eq(true), any())).thenReturn(new SkillPublishService.PublishResult(21L, "demo", version));
        SkillOrigin origin = new SkillOrigin(21L, 11L, "skillhub-cn", "demo", "author",
                "https://skillhub.cn/skills/demo", null);
        ReflectionTestUtils.setField(origin, "id", 41L);
        when(originRepository.save(any())).thenReturn(origin);
        when(client.detailFresh("demo")).thenReturn(detail());
        ExternalSkillImportRequest request = new ExternalSkillImportRequest(
                "team-a", null, sha(archive), shaWarnings(archive), false, false);

        var result = service.importVersion(
                "skillhub-cn", "demo", "1.0.0", request, "user-1", Set.of(), Map.of());

        assertThat(result.status()).isEqualTo("UPLOADED");
        assertThat(result.nextAction()).isEqualTo("NONE");
        verify(publishService).publishFromEntries(
                eq("team-a"), any(), eq("user-1"), eq(SkillVisibility.PRIVATE), eq(Set.of()),
                eq(true), any());
    }

    @Test
    void missingLicenseBlocksOrdinaryUser() {
        byte[] archive = "archive-a".getBytes(StandardCharsets.UTF_8);
        when(client.download("demo", "1.0.0")).thenReturn(archive);
        when(metadataParser.parse(any())).thenReturn(
                new SkillMetadata("demo", "Demo", "1.0.0", "Body", Map.of()));
        ExternalSkillImportRequest request = new ExternalSkillImportRequest(
                "team-a", "PRIVATE", sha(archive), shaWarnings(archive), false, false);

        assertThatThrownBy(() -> service.importVersion(
                "skillhub-cn", "demo", "1.0.0", request, "user-1", Set.of(), Map.of()))
                .isInstanceOf(DomainForbiddenException.class);
    }

    private ExternalSkillDetail detail() {
        var summary = new ExternalSkillSummary(
                "skillhub-cn", "demo", "Demo", "Summary", "", "author", "1.0.0",
                "dev-programming", "", "https://skillhub.cn/skills/demo", 1, 1, 1, false);
        return new ExternalSkillDetail(
                summary, new ExternalSkillVersion("1.0.0", "", 1L, new ObjectMapper().createObjectNode()),
                new ObjectMapper().createObjectNode());
    }

    private String shaWarnings(byte[] archive) {
        return sha("\n".concat(sha(archive)).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
