package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.usage.SkillUsageDaily;
import com.iflytek.skillhub.domain.usage.SkillUsageDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for the per-UTC-day usage aggregates maintained by the rollup task.
 */
@Repository
public interface SkillUsageDailyJpaRepository extends JpaRepository<SkillUsageDaily, SkillUsageDailyId> {
}
