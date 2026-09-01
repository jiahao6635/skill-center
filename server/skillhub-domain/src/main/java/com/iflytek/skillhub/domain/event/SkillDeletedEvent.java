package com.iflytek.skillhub.domain.event;

/**
 * Emitted after a skill hard-delete has been persisted so search and other
 * read models can drop the now-missing aggregate.
 */
public record SkillDeletedEvent(Long skillId) {
}
