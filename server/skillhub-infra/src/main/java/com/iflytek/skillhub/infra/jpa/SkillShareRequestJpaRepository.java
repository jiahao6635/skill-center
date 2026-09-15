package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillShareRequest;
import com.iflytek.skillhub.domain.skill.SkillShareStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillShareRequestJpaRepository extends JpaRepository<SkillShareRequest, Long> {
    Optional<SkillShareRequest> findBySkillIdAndIdempotencyKey(Long skillId, String key);
    Optional<SkillShareRequest> findFirstBySkillIdOrderByIdDesc(Long skillId);
    List<SkillShareRequest> findTop100ByStatusAndIdGreaterThanOrderByIdAsc(SkillShareStatus status, Long afterId);
}
