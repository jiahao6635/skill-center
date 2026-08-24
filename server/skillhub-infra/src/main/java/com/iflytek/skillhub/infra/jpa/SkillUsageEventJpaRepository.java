package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.usage.SkillUsageEvent;
import com.iflytek.skillhub.domain.usage.SkillUsageEventRepository;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed usage-event repository. The insert is a native ON CONFLICT DO
 * NOTHING against the partial unique dedup index, so a window duplicate
 * reports 0 affected rows instead of poisoning the surrounding transaction.
 */
@Repository
public interface SkillUsageEventJpaRepository
        extends JpaRepository<SkillUsageEvent, Long>, JpaSpecificationExecutor<SkillUsageEvent>, SkillUsageEventRepository {

    @Override
    @Modifying
    @Transactional
    @Query(
            value = """
                    INSERT INTO skill_usage_event (
                        occurred_at, action, skill_id, skill_version_id, namespace_id,
                        actor_user_id, actor_key, actor_kind, client, auth_method,
                        request_id, client_ip, user_agent, dedup_key, payload_json
                    ) VALUES (
                        :occurredAt, :action, :skillId, :skillVersionId, :namespaceId,
                        :actorUserId, :actorKey, :actorKind, :client, :authMethod,
                        :requestId, :clientIp, :userAgent, :dedupKey, CAST(:payloadJson AS jsonb)
                    )
                    ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING
                    """,
            nativeQuery = true
    )
    int insert(@Param("occurredAt") Instant occurredAt,
               @Param("action") String action,
               @Param("skillId") Long skillId,
               @Param("skillVersionId") Long skillVersionId,
               @Param("namespaceId") Long namespaceId,
               @Param("actorUserId") String actorUserId,
               @Param("actorKey") String actorKey,
               @Param("actorKind") String actorKind,
               @Param("client") String client,
               @Param("authMethod") String authMethod,
               @Param("requestId") String requestId,
               @Param("clientIp") String clientIp,
               @Param("userAgent") String userAgent,
               @Param("dedupKey") String dedupKey,
               @Param("payloadJson") String payloadJson);
}
