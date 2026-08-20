package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA-backed repository for skill version history and status-oriented version queries.
 */
@Repository
public interface SkillVersionJpaRepository extends JpaRepository<SkillVersion, Long>, SkillVersionRepository {
    List<SkillVersion> findByIdIn(List<Long> ids);
    List<SkillVersion> findBySkillId(Long skillId);
    List<SkillVersion> findBySkillIdIn(List<Long> skillIds);
    List<SkillVersion> findBySkillIdInAndStatusOrderByCreatedAtDesc(List<Long> skillIds, SkillVersionStatus status);
    Optional<SkillVersion> findBySkillIdAndVersion(Long skillId, String version);

    @Override
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        DELETE FROM SkillVersion version
        WHERE version.id = :versionId
          AND version.skillId = :skillId
          AND version.status IN :statuses
    """)
    int deleteIfStatusIn(@Param("versionId") Long versionId,
                         @Param("skillId") Long skillId,
                         @Param("statuses") Collection<SkillVersionStatus> statuses);

    @Override
    default List<SkillVersion> findBySkillIdAndStatus(Long skillId, SkillVersionStatus status) {
        return findBySkillIdAndStatusOrderByCreatedAtDesc(skillId, status);
    }

    @Override
    default List<SkillVersion> findBySkillIdInAndStatus(List<Long> skillIds, SkillVersionStatus status) {
        return findBySkillIdInAndStatusOrderByCreatedAtDesc(skillIds, status);
    }

    List<SkillVersion> findBySkillIdAndStatusOrderByCreatedAtDesc(Long skillId, SkillVersionStatus status);
    Page<SkillVersion> findBySkillIdAndStatus(Long skillId, SkillVersionStatus status, Pageable pageable);
    void deleteBySkillId(Long skillId);
}
