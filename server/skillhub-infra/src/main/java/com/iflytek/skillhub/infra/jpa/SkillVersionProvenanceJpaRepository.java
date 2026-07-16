package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenance;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillVersionProvenanceJpaRepository extends JpaRepository<SkillVersionProvenance, Long> {
    Optional<SkillVersionProvenance> findBySkillOriginIdAndSourceVersion(Long skillOriginId, String sourceVersion);
}
