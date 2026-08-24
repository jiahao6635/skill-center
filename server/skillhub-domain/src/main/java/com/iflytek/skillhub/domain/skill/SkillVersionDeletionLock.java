package com.iflytek.skillhub.domain.skill;

import java.util.Objects;
import java.util.Optional;

/**
 * Serializes destructive version mutations for one skill and reloads the aggregate root after the
 * coordination lock has been acquired. Version deletion, replacement publish, and whole-skill
 * deletion must share this coordination point so their child/skill row locks cannot interleave.
 *
 * <p>The caller must already have an active transaction and must invoke this operation before any
 * write or row lock in that transaction. The returned coordination lock is held until the caller's
 * transaction completes.
 */
public interface SkillVersionDeletionLock {

    Optional<Skill> lockAndRefresh(Long skillId);

    default Optional<Skill> lockAndRefresh(Skill skill) {
        Objects.requireNonNull(skill, "skill must not be null");
        return lockAndRefresh(skill.getId());
    }
}
