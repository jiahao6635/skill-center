package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.skill.*;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Refreshes requests after the common skill mutation lock to avoid stale decisions after waiting. */
@Repository
public class JpaSkillShareRequestRepository implements SkillShareRequestRepository {
    private final SkillShareRequestJpaRepository delegate;
    private final EntityManager entityManager;
    public JpaSkillShareRequestRepository(SkillShareRequestJpaRepository delegate, EntityManager entityManager) {
        this.delegate = delegate; this.entityManager = entityManager;
    }
    public SkillShareRequest save(SkillShareRequest request) { return delegate.save(request); }
    public void refresh(SkillShareRequest request) { entityManager.refresh(request); }
    public Optional<SkillShareRequest> findById(Long id) { return delegate.findById(id); }
    public Optional<SkillShareRequest> findBySkillIdAndIdempotencyKey(Long id, String key) {
        return delegate.findBySkillIdAndIdempotencyKey(id, key);
    }
    public Optional<SkillShareRequest> findFirstBySkillIdOrderByIdDesc(Long id) {
        return delegate.findFirstBySkillIdOrderByIdDesc(id);
    }
    public List<SkillShareRequest> findTop100ByStatusAndIdGreaterThanOrderByIdAsc(SkillShareStatus status, Long afterId) {
        return delegate.findTop100ByStatusAndIdGreaterThanOrderByIdAsc(status, afterId);
    }
}
