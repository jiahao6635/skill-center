package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenance;
import com.iflytek.skillhub.domain.skill.origin.SkillVersionProvenanceRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSkillVersionProvenanceRepositoryAdapter implements SkillVersionProvenanceRepository {
    private final SkillVersionProvenanceJpaRepository repository;

    public JpaSkillVersionProvenanceRepositoryAdapter(SkillVersionProvenanceJpaRepository repository) { this.repository = repository; }
    public Optional<SkillVersionProvenance> findBySkillOriginIdAndSourceVersion(Long skillOriginId, String sourceVersion) {
        return repository.findBySkillOriginIdAndSourceVersion(skillOriginId, sourceVersion);
    }
    public SkillVersionProvenance save(SkillVersionProvenance provenance) { return repository.save(provenance); }
}
