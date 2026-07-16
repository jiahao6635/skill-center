package com.iflytek.skillhub.domain.skill.origin;

import java.util.Optional;

public interface SkillOriginRepository {
    Optional<SkillOrigin> findBySkillId(Long skillId);
    Optional<SkillOrigin> findByNamespaceIdAndProviderAndSourceSlug(Long namespaceId, String provider, String sourceSlug);
    SkillOrigin save(SkillOrigin origin);
}
