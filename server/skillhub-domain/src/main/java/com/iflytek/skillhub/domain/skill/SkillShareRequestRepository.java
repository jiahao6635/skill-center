package com.iflytek.skillhub.domain.skill;

import java.util.List;
import java.util.Optional;

public interface SkillShareRequestRepository {
    SkillShareRequest save(SkillShareRequest request);
    void refresh(SkillShareRequest request);
    Optional<SkillShareRequest> findById(Long id);
    Optional<SkillShareRequest> findBySkillIdAndIdempotencyKey(Long skillId, String key);
    Optional<SkillShareRequest> findFirstBySkillIdOrderByIdDesc(Long skillId);
    List<SkillShareRequest> findTop100ByStatusAndIdGreaterThanOrderByIdAsc(SkillShareStatus status, Long afterId);
}
