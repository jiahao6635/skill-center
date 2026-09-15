-- Effective distribution is independent of upload/review intent. Unknown private history
-- stays private; a completed share grants access to exactly one existing version.
ALTER TABLE skill_version ADD COLUMN distribution_visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
UPDATE skill_version v SET distribution_visibility = s.visibility
FROM skill s WHERE s.id = v.skill_id AND s.visibility <> 'PRIVATE'
  AND v.status IN ('PUBLISHED', 'YANKED')
  AND (v.requested_visibility IS NULL OR v.requested_visibility <> 'PRIVATE');
ALTER TABLE skill_version ADD COLUMN sharing_request_id BIGINT;
ALTER TABLE skill ADD COLUMN private_source_namespace_id BIGINT REFERENCES namespace(id);
CREATE UNIQUE INDEX uq_skill_private_source_owner ON skill(private_source_namespace_id, slug, owner_id)
  WHERE private_source_namespace_id IS NOT NULL;

CREATE TABLE skill_share_request (
  id BIGSERIAL PRIMARY KEY,
  skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  skill_version_id BIGINT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  source_namespace_id BIGINT NOT NULL REFERENCES namespace(id),
  target_namespace_id BIGINT NOT NULL REFERENCES namespace(id),
  target_visibility VARCHAR(20) NOT NULL CHECK (target_visibility IN ('PUBLIC', 'NAMESPACE_ONLY')),
  submitted_by VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  security_audit_id BIGINT,
  review_task_id BIGINT,
  error_code VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  revision INTEGER NOT NULL DEFAULT 0,
  UNIQUE (skill_id, idempotency_key)
);
CREATE UNIQUE INDEX uq_skill_share_active ON skill_share_request(skill_id)
  WHERE status IN ('SCANNING', 'PENDING_REVIEW');
CREATE INDEX idx_skill_share_status ON skill_share_request(status, id);
ALTER TABLE review_task ADD COLUMN share_request_id BIGINT REFERENCES skill_share_request(id) ON DELETE CASCADE;
-- All publishers serialize namespace name claims on the namespace row before their
-- skill mutation lock. The existing owner-aware uniqueness constraint stays intact.

ALTER TABLE skill ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE skill_version ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
-- Separate personal preview from the published pointer, including legacy private uploads.
UPDATE skill s SET latest_version_id = (
    SELECT v.id FROM skill_version v WHERE v.skill_id = s.id AND v.status = 'PUBLISHED'
      AND (s.visibility = 'PRIVATE' OR v.distribution_visibility <> 'PRIVATE')
    ORDER BY v.published_at DESC NULLS LAST, v.created_at DESC, v.id DESC LIMIT 1
) WHERE latest_version_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM skill_version v WHERE v.id = s.latest_version_id AND v.status = 'PUBLISHED'
      AND (s.visibility = 'PRIVATE' OR v.distribution_visibility <> 'PRIVATE')
);
