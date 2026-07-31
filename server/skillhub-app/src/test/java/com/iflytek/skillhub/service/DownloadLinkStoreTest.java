package com.iflytek.skillhub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadLinkStoreTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;

    private DownloadLinkStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new DownloadLinkStore(redisTemplate);
    }

    @Test
    void save_WritesWithPrefixedKeyAndTtl() {
        DownloadLinkStore.DownloadLinkData data =
                new DownloadLinkStore.DownloadLinkData("https://oss/x", 1L, 10L, "skill-1.0.0.zip", true);

        store.save("token-abc", data);

        verify(valueOperations).set("skillhub:download-link:token-abc", data,
                DownloadLinkStore.TTL_MINUTES, TimeUnit.MINUTES);
    }

    @Test
    void get_ReturnsStoredData() {
        DownloadLinkStore.DownloadLinkData data =
                new DownloadLinkStore.DownloadLinkData("https://oss/x", 1L, 10L, "skill-1.0.0.zip", true);
        when(valueOperations.get("skillhub:download-link:token-abc")).thenReturn(data);

        DownloadLinkStore.DownloadLinkData result = store.get("token-abc");

        assertNotNull(result);
        assertEquals("https://oss/x", result.getPresignedUrl());
        assertEquals(1L, result.getSkillId());
        assertEquals(10L, result.getVersionId());
        assertEquals("skill-1.0.0.zip", result.getFilename());
        assertTrue(result.isPublished());
    }

    @Test
    void get_ReturnsNullWhenMissing() {
        when(valueOperations.get("skillhub:download-link:missing")).thenReturn(null);

        assertNull(store.get("missing"));
    }

    @Test
    void markCountedIfAbsent_FirstCall_ReturnsTrueAndSetsMarkerWithTtl() {
        when(valueOperations.setIfAbsent("skillhub:download-link-counted:token-abc", Boolean.TRUE,
                DownloadLinkStore.TTL_MINUTES, TimeUnit.MINUTES)).thenReturn(true);

        assertTrue(store.markCountedIfAbsent("token-abc"));

        verify(valueOperations).setIfAbsent("skillhub:download-link-counted:token-abc", Boolean.TRUE,
                DownloadLinkStore.TTL_MINUTES, TimeUnit.MINUTES);
    }

    @Test
    void markCountedIfAbsent_AlreadyCounted_ReturnsFalse() {
        when(valueOperations.setIfAbsent("skillhub:download-link-counted:token-abc", Boolean.TRUE,
                DownloadLinkStore.TTL_MINUTES, TimeUnit.MINUTES)).thenReturn(false);

        assertFalse(store.markCountedIfAbsent("token-abc"));
    }
}
