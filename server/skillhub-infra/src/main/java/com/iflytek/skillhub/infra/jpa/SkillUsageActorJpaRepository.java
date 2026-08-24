package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.usage.SkillUsageActor;
import com.iflytek.skillhub.domain.usage.SkillUsageActorId;
import com.iflytek.skillhub.domain.usage.SkillUsageActorRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed lifetime unique-actor repository with the frozen upsert.
 */
@Repository
public interface SkillUsageActorJpaRepository
        extends JpaRepository<SkillUsageActor, SkillUsageActorId>, SkillUsageActorRepository {

    @Override
    @Modifying
    @Transactional
    @Query(
            value = """
                    INSERT INTO skill_usage_actor AS a
                        (skill_id, action, actor_key, first_at, last_at, last_client)
                    VALUES
                        (:skillId, :action, :actorKey, :occurredAt, :occurredAt, :client)
                    ON CONFLICT (skill_id, action, actor_key) DO UPDATE SET
                        last_at     = EXCLUDED.last_at,
                        last_client = EXCLUDED.last_client,
                        first_at    = LEAST(a.first_at, EXCLUDED.first_at)
                    """,
            nativeQuery = true
    )
    void upsert(@Param("skillId") Long skillId,
                @Param("action") String action,
                @Param("actorKey") String actorKey,
                @Param("occurredAt") Instant occurredAt,
                @Param("client") String client);
}
