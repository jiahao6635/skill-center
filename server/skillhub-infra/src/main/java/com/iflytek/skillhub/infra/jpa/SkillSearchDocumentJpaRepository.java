package com.iflytek.skillhub.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SkillSearchDocumentJpaRepository extends JpaRepository<SkillSearchDocumentEntity, Long> {
    Optional<SkillSearchDocumentEntity> findBySkillId(Long skillId);
    List<SkillSearchDocumentEntity> findBySkillIdIn(Collection<Long> skillIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM SkillSearchDocumentEntity document WHERE document.skillId = :skillId")
    void deleteBySkillId(@Param("skillId") Long skillId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM SkillSearchDocumentEntity document WHERE document.skillId NOT IN :skillIds")
    void deleteBySkillIdNotIn(@Param("skillIds") Collection<Long> skillIds);
}
