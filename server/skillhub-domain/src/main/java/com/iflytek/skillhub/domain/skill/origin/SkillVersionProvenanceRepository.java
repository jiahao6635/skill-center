package com.iflytek.skillhub.domain.skill.origin;

import java.util.Optional;

public interface SkillVersionProvenanceRepository {
    Optional<SkillVersionProvenance> findBySkillOriginIdAndSourceVersion(Long skillOriginId, String sourceVersion);
    SkillVersionProvenance save(SkillVersionProvenance provenance);
}
