package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.usage.SkillUsageRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the per-skill usage rollup refreshed by the rollup task.
 */
@Repository
public interface SkillUsageRollupJpaRepository extends JpaRepository<SkillUsageRollup, Long> {
}
