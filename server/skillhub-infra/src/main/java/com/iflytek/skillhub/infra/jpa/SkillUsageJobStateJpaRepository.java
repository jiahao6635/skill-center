package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.usage.SkillUsageJobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for usage background-job watermark / lock rows.
 */
@Repository
public interface SkillUsageJobStateJpaRepository extends JpaRepository<SkillUsageJobState, String> {
}
