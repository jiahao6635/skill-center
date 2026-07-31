package com.iflytek.skillhub.service;

import com.iflytek.skillhub.domain.namespace.NamespaceRole;
import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.domain.skill.service.SkillDownloadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillDownloadLinkServiceTest {

    @Mock
    private SkillDownloadService skillDownloadService;
    @Mock
    private DownloadLinkStore downloadLinkStore;

    private SkillDownloadLinkService service;

    @BeforeEach
    void setUp() {
        service = new SkillDownloadLinkService(skillDownloadService, downloadLinkStore);
    }

    @Test
    void issueDownloadLink_PresignedUrl_ReturnsRedirectTokenAndStores() {
        Map<Long, NamespaceRole> roles = Map.of(1L, NamespaceRole.MEMBER);
        when(skillDownloadService.presignDownload("ns", "slug", "1.0.0", "user-1", roles))
                .thenReturn(new SkillDownloadService.PresignedDownload(
                        "https://oss/presigned", 1L, 10L, "slug-1.0.0.zip", true));

        SkillDownloadLinkService.IssueResult result =
                service.issueDownloadLink("ns", "slug", "1.0.0", "user-1", roles);

        assertTrue(result.isRedirect());
        assertNotNull(result.token());
        assertNull(result.fallbackPath());
        assertNotNull(result.expiresAt());

        ArgumentCaptor<DownloadLinkStore.DownloadLinkData> captor =
                ArgumentCaptor.forClass(DownloadLinkStore.DownloadLinkData.class);
        verify(downloadLinkStore).save(eq(result.token()), captor.capture());
        assertEquals("https://oss/presigned", captor.getValue().getPresignedUrl());
        assertEquals(1L, captor.getValue().getSkillId());
        assertEquals(10L, captor.getValue().getVersionId());
        assertTrue(captor.getValue().isPublished());
    }

    @Test
    void issueDownloadLink_NullPresignedUrl_ReturnsFallbackPath() {
        when(skillDownloadService.presignDownload("ns", "slug", null, "user-1", Map.of()))
                .thenReturn(new SkillDownloadService.PresignedDownload(null, 1L, 10L, "slug-1.0.0.zip", true));

        SkillDownloadLinkService.IssueResult result =
                service.issueDownloadLink("ns", "slug", null, "user-1", Map.of());

        assertFalse(result.isRedirect());
        assertNull(result.token());
        assertEquals("/api/cli/v1/skills/ns/slug/download", result.fallbackPath());
        verify(downloadLinkStore, never()).save(anyString(), any());
    }

    @Test
    void resolveForRedirect_PublishedFirstHit_RecordsDownloadAndReturnsUrl() {
        DownloadLinkStore.DownloadLinkData data =
                new DownloadLinkStore.DownloadLinkData("https://oss/presigned", 5L, 50L, "f.zip", true);
        when(downloadLinkStore.get("token-1")).thenReturn(data);
        when(downloadLinkStore.markCountedIfAbsent("token-1")).thenReturn(true);

        String url = service.resolveForRedirect("token-1");

        assertEquals("https://oss/presigned", url);
        verify(skillDownloadService).recordDownloadById(5L, 50L);
    }

    @Test
    void resolveForRedirect_AlreadyCounted_RedirectsWithoutRecordingAgain() {
        DownloadLinkStore.DownloadLinkData data =
                new DownloadLinkStore.DownloadLinkData("https://oss/presigned", 5L, 50L, "f.zip", true);
        when(downloadLinkStore.get("token-1")).thenReturn(data);
        when(downloadLinkStore.markCountedIfAbsent("token-1")).thenReturn(false);

        String url = service.resolveForRedirect("token-1");

        assertEquals("https://oss/presigned", url);
        verify(skillDownloadService, never()).recordDownloadById(anyLong(), anyLong());
    }

    @Test
    void resolveForRedirect_DraftVersion_RedirectsWithoutRecording() {
        DownloadLinkStore.DownloadLinkData data =
                new DownloadLinkStore.DownloadLinkData("https://oss/presigned", 5L, 50L, "f.zip", false);
        when(downloadLinkStore.get("token-1")).thenReturn(data);

        String url = service.resolveForRedirect("token-1");

        assertEquals("https://oss/presigned", url);
        verify(downloadLinkStore, never()).markCountedIfAbsent(anyString());
        verify(skillDownloadService, never()).recordDownloadById(anyLong(), anyLong());
    }

    @Test
    void resolveForRedirect_UnknownToken_ThrowsNotFound() {
        when(downloadLinkStore.get("missing")).thenReturn(null);

        assertThrows(DomainNotFoundException.class, () -> service.resolveForRedirect("missing"));
        verify(skillDownloadService, never()).recordDownloadById(anyLong(), anyLong());
    }
}
