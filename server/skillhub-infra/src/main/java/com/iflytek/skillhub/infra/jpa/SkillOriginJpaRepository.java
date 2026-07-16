package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.origin.SkillOrigin;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillOriginJpaRepository extends JpaRepository<SkillOrigin, Long> {
    Optional<SkillOrigin> findBySkillId(Long skillId);
    Optional<SkillOrigin> findByNamespaceIdAndProviderAndSourceSlug(Long namespaceId, String provider, String sourceSlug);
}
