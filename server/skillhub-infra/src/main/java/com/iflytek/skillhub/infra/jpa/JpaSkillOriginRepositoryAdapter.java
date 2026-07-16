package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.origin.SkillOrigin;
import com.iflytek.skillhub.domain.skill.origin.SkillOriginRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSkillOriginRepositoryAdapter implements SkillOriginRepository {
    private final SkillOriginJpaRepository repository;

    public JpaSkillOriginRepositoryAdapter(SkillOriginJpaRepository repository) { this.repository = repository; }
    public Optional<SkillOrigin> findBySkillId(Long skillId) { return repository.findBySkillId(skillId); }
    public Optional<SkillOrigin> findByNamespaceIdAndProviderAndSourceSlug(Long namespaceId, String provider, String sourceSlug) {
        return repository.findByNamespaceIdAndProviderAndSourceSlug(namespaceId, provider, sourceSlug);
    }
    public SkillOrigin save(SkillOrigin origin) { return repository.save(origin); }
}
