package com.iflytek.skillhub.domain.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillUsageRecorderTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-21T08:00:00Z");

    @Mock
    private SkillUsageEventRepository eventRepository;

    @Mock
    private SkillUsageActorRepository actorRepository;

    private SkillUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new SkillUsageRecorder(eventRepository, actorRepository);
    }

    private static SkillUsageRequestContext webContext() {
        return new SkillUsageRequestContext(
                SkillUsageClient.WEB, SkillUsageAuthMethod.SESSION, "req-1", "10.0.0.1", "JUnit");
    }

    private static UsageCommand command(SkillUsageAction action, Long skillId,
                                        String actorUserId, String actorKey, String dedupKey) {
        return new UsageCommand(
                OCCURRED_AT, action, skillId, skillId == null ? null : 20L, 30L,
                actorUserId, actorKey,
                actorUserId != null ? SkillUsageActorKind.USER : SkillUsageActorKind.ANONYMOUS,
                webContext(), dedupKey, "{}");
    }

    private void stubInsert(int affectedRows) {
        when(eventRepository.insert(any(), anyString(), any(), any(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(affectedRows);
    }

    @Test
    void download_insertsEventAndUpsertsActor() {
        stubInsert(1);

        recorder.record(command(SkillUsageAction.DOWNLOAD, 10L, "user-1", "user:user-1",
                "DOWNLOAD:10:20:user:user-1:123"));

        verify(eventRepository).insert(eq(OCCURRED_AT), eq("DOWNLOAD"), eq(10L), eq(20L), eq(30L),
                eq("user-1"), eq("user:user-1"), eq("USER"), eq("WEB"), eq("SESSION"),
                eq("req-1"), eq("10.0.0.1"), eq("JUnit"),
                eq("DOWNLOAD:10:20:user:user-1:123"), eq("{}"));
        verify(actorRepository).upsert(10L, "DOWNLOAD", "user:user-1", OCCURRED_AT, "WEB");
    }

    @Test
    void view_upsertsActor() {
        stubInsert(1);

        recorder.record(command(SkillUsageAction.VIEW, 10L, null, "anon:" + "a".repeat(64), null));

        verify(actorRepository).upsert(eq(10L), eq("VIEW"), eq("anon:" + "a".repeat(64)),
                eq(OCCURRED_AT), eq("WEB"));
    }

    @Test
    void search_withNullSkillId_insertsEventAndNeverTouchesActor() {
        stubInsert(1);

        recorder.record(command(SkillUsageAction.SEARCH, null, "user-1", "user:user-1",
                "SEARCH:deadbeef:user:user-1:123"));

        verify(eventRepository).insert(any(), eq("SEARCH"), eq((Long) null), eq((Long) null), eq(30L),
                any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(),
                any(), anyString());
        verifyNoInteractions(actorRepository);
    }

    @Test
    void upload_neverUpsertsActor() {
        stubInsert(1);

        recorder.record(command(SkillUsageAction.UPLOAD, 10L, "user-1", "user:user-1", null));

        verifyNoInteractions(actorRepository);
    }

    @Test
    void publish_neverUpsertsActor() {
        stubInsert(1);

        recorder.record(command(SkillUsageAction.PUBLISH, 10L, "user-1", "user:user-1", null));

        verifyNoInteractions(actorRepository);
    }

    @Test
    void dedupConflict_skipsActorUpsert() {
        stubInsert(0);

        recorder.record(command(SkillUsageAction.DOWNLOAD, 10L, "user-1", "user:user-1",
                "DOWNLOAD:10:20:user:user-1:123"));

        verifyNoInteractions(actorRepository);
    }

    @Test
    void otherIntegrityViolations_propagate() {
        when(eventRepository.insert(any(), anyString(), any(), any(), any(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), anyString()))
                .thenThrow(new DataIntegrityViolationException("fk violation"));

        assertThatThrownBy(() -> recorder.record(
                command(SkillUsageAction.DOWNLOAD, 999L, "user-1", "user:user-1", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(actorRepository);
    }

    @Test
    void maxLengthUserId_producesActorKeyAndDedupKeyWithinColumnBounds() {
        stubInsert(1);
        String userId = "u".repeat(128);
        String actorKey = "user:" + userId;
        // §5.6 plaintext dedup key: {action}:{subject}:{actor_key}:{bucket}
        String dedupKey = "DOWNLOAD:10:20:" + actorKey + ":1755763200";
        assertThat(actorKey).hasSize(133);
        assertThat(dedupKey.length()).isGreaterThan(128).isLessThanOrEqualTo(512);

        recorder.record(command(SkillUsageAction.DOWNLOAD, 10L, userId, actorKey, dedupKey));

        ArgumentCaptor<String> dedupCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventRepository).insert(any(), anyString(), any(), any(), any(), eq(userId),
                eq(actorKey), anyString(), anyString(), anyString(), any(), any(), any(),
                dedupCaptor.capture(), anyString());
        assertThat(dedupCaptor.getValue()).isEqualTo(dedupKey);
        verify(actorRepository).upsert(eq(10L), eq("DOWNLOAD"), eq(actorKey), eq(OCCURRED_AT), eq("WEB"));
    }

    @Test
    void nullRequestContext_defaultsClientAndAuthMethodToUnknown() {
        stubInsert(1);

        recorder.record(new UsageCommand(OCCURRED_AT, SkillUsageAction.PUBLISH, 10L, 20L, 30L,
                "user-1", "user:user-1", SkillUsageActorKind.USER, null, null, null));

        verify(eventRepository).insert(eq(OCCURRED_AT), eq("PUBLISH"), eq(10L), eq(20L), eq(30L),
                eq("user-1"), eq("user:user-1"), eq("USER"), eq("UNKNOWN"), eq("UNKNOWN"),
                eq((String) null), eq((String) null), eq((String) null), eq((String) null), eq("{}"));
    }

    @Test
    void oversizedUserAgent_isTruncatedTo512() {
        stubInsert(1);
        SkillUsageRequestContext context = new SkillUsageRequestContext(
                SkillUsageClient.CLI, SkillUsageAuthMethod.ANONYMOUS, null, "10.0.0.1", "x".repeat(600));

        recorder.record(new UsageCommand(OCCURRED_AT, SkillUsageAction.DOWNLOAD, 10L, 20L, 30L,
                null, "anon:" + "b".repeat(32), SkillUsageActorKind.ANONYMOUS, context, null, null));

        ArgumentCaptor<String> userAgentCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventRepository).insert(any(), anyString(), anyLong(), anyLong(), anyLong(), any(),
                anyString(), anyString(), anyString(), anyString(), any(), any(),
                userAgentCaptor.capture(), any(), anyString());
        assertThat(userAgentCaptor.getValue()).hasSize(512);
    }

    @Test
    void blankActorKey_isRejected() {
        assertThatThrownBy(() -> recorder.record(new UsageCommand(OCCURRED_AT,
                SkillUsageAction.VIEW, 10L, null, null, null, " ",
                SkillUsageActorKind.ANONYMOUS, webContext(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(eventRepository, actorRepository);
    }
}
