package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillPublicationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-17T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillVersionRepository skillVersionRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SkillPublicationService service;

    @BeforeEach
    void setUp() {
        service = new SkillPublicationService(
                skillRepository, skillVersionRepository, eventPublisher, objectMapper, CLOCK);
    }

    @Test
    void publishVersion_movesVersionToPublishedAndUpdatesSkill() throws Exception {
        Skill skill = new Skill(20L, "my-skill", "owner-1", SkillVisibility.PUBLIC);
        setId(skill, 8L);
        SkillVersion version = new SkillVersion(8L, "1.1.0", "publisher-1");
        setId(version, 42L);
        version.setStatus(SkillVersionStatus.SCANNING);
        version.setAutoPublishOnScanPass(true);
        version.setRequestedVisibility(SkillVisibility.NAMESPACE_ONLY);
        version.setParsedMetadataJson(objectMapper.writeValueAsString(
                new SkillMetadata("Approved Name", "Approved Summary", "1.1.0", "Body", Map.of())));

        when(skillRepository.findByNamespaceIdAndSlug(20L, "my-skill")).thenReturn(List.of(skill));

        service.publishVersion(skill, version, "actor-9");

        assertThat(version.getStatus()).isEqualTo(SkillVersionStatus.PUBLISHED);
        assertThat(version.getPublishedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(version.isAutoPublishOnScanPass()).isFalse();
        assertThat(skill.getLatestVersionId()).isEqualTo(42L);
        assertThat(skill.getVisibility()).isEqualTo(SkillVisibility.NAMESPACE_ONLY);
        assertThat(skill.getDisplayName()).isEqualTo("Approved Name");
        assertThat(skill.getSummary()).isEqualTo("Approved Summary");
        assertThat(skill.getUpdatedBy()).isEqualTo("actor-9");
        verify(skillVersionRepository).save(version);
        verify(skillRepository).save(skill);

        ArgumentCaptor<SkillPublishedEvent> eventCaptor = ArgumentCaptor.forClass(SkillPublishedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().skillId()).isEqualTo(8L);
        assertThat(eventCaptor.getValue().versionId()).isEqualTo(42L);
        assertThat(eventCaptor.getValue().publisherId()).isEqualTo("actor-9");
    }

    @Test
    void publishVersion_rejectsWhenOtherOwnerAlreadyPublishedSameSlug() throws Exception {
        Skill skill = new Skill(20L, "my-skill", "owner-1", SkillVisibility.PUBLIC);
        setId(skill, 8L);
        Skill other = new Skill(20L, "my-skill", "other-owner", SkillVisibility.PUBLIC);
        setId(other, 99L);
        SkillVersion version = new SkillVersion(8L, "1.1.0", "publisher-1");
        setId(version, 42L);
        SkillVersion otherPublished = new SkillVersion(99L, "1.0.0", "other-owner");
        otherPublished.setStatus(SkillVersionStatus.PUBLISHED);

        when(skillRepository.findByNamespaceIdAndSlug(20L, "my-skill")).thenReturn(List.of(skill, other));
        when(skillVersionRepository.findBySkillIdAndStatus(99L, SkillVersionStatus.PUBLISHED))
                .thenReturn(List.of(otherPublished));

        assertThatThrownBy(() -> service.publishVersion(skill, version, "actor-9"))
                .isInstanceOf(DomainBadRequestException.class)
                .extracting(ex -> ((DomainBadRequestException) ex).messageCode())
                .isEqualTo("error.skill.approve.nameConflict");
        assertThat(version.getStatus()).isNotEqualTo(SkillVersionStatus.PUBLISHED);
    }

    private void setId(Object target, Long id) throws Exception {
        Field field = target.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(target, id);
    }
}
