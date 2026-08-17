package com.iflytek.skillhub.domain.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.event.SkillPublishedEvent;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.metadata.SkillMetadata;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Publishes a prepared {@link SkillVersion}, moving it to {@code PUBLISHED} and updating the owning
 * {@link Skill}'s published pointer and metadata.
 *
 * <p>This is a deliberately dependency-light collaborator so that both {@code ReviewService}
 * (human-review approval) and {@code SecurityScanService} (review-exempt auto-publish on a SAFE scan)
 * can share the exact same publication semantics without introducing a dependency cycle. It must not
 * depend on any service that (directly or transitively) depends on it.
 */
@Service
public class SkillPublicationService {

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SkillPublicationService(SkillRepository skillRepository,
                                   SkillVersionRepository skillVersionRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper,
                                   Clock clock) {
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Publishes {@code version} under {@code skill}, attributing the action to {@code actorId}.
     *
     * <p>Guards against another owner having already published the same slug, transitions the version
     * to {@code PUBLISHED}, moves the skill's latest-version pointer, applies the version's requested
     * visibility and parsed metadata, and emits a {@link SkillPublishedEvent}.
     */
    @Transactional
    public void publishVersion(Skill skill, SkillVersion version, String actorId) {
        // No other owner may already hold a published skill with the same slug.
        List<Skill> sameSlugSkills = skillRepository.findByNamespaceIdAndSlug(skill.getNamespaceId(), skill.getSlug());
        for (Skill other : sameSlugSkills) {
            if (!other.getId().equals(skill.getId())) {
                boolean otherHasPublished = !skillVersionRepository
                        .findBySkillIdAndStatus(other.getId(), SkillVersionStatus.PUBLISHED)
                        .isEmpty();
                if (otherHasPublished) {
                    throw new DomainBadRequestException("error.skill.approve.nameConflict", skill.getSlug());
                }
            }
        }

        version.setStatus(SkillVersionStatus.PUBLISHED);
        version.setPublishedAt(Instant.now(clock));
        version.setAutoPublishOnScanPass(false);
        skillVersionRepository.save(version);

        skill.setLatestVersionId(version.getId());
        if (version.getRequestedVisibility() != null) {
            skill.setVisibility(version.getRequestedVisibility());
        }
        applyPublishedMetadata(skill, version);
        skill.setUpdatedBy(actorId);
        skillRepository.save(skill);

        eventPublisher.publishEvent(new SkillPublishedEvent(skill.getId(), version.getId(), actorId));
    }

    private void applyPublishedMetadata(Skill skill, SkillVersion skillVersion) {
        String metadataJson = skillVersion.getParsedMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }

        try {
            SkillMetadata metadata = objectMapper.readValue(metadataJson, SkillMetadata.class);
            skill.setDisplayName(metadata.name());
            skill.setSummary(metadata.description());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize skill metadata", e);
        }
    }
}
