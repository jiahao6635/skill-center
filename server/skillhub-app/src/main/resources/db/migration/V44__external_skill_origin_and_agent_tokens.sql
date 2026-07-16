CREATE TABLE skill_origin (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    source_slug VARCHAR(255) NOT NULL,
    source_owner VARCHAR(255),
    source_url TEXT,
    upstream_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_origin_skill UNIQUE (skill_id),
    CONSTRAINT uk_skill_origin_coordinate UNIQUE (namespace_id, provider, source_slug)
);

CREATE TABLE skill_version_provenance (
    id BIGSERIAL PRIMARY KEY,
    skill_origin_id BIGINT NOT NULL REFERENCES skill_origin(id) ON DELETE CASCADE,
    skill_version_id BIGINT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
    source_version VARCHAR(128) NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL,
    license_status VARCHAR(32) NOT NULL,
    license_expression VARCHAR(255),
    security_report_json JSONB,
    imported_by VARCHAR(128) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_version_provenance_local UNIQUE (skill_version_id),
    CONSTRAINT uk_skill_version_provenance_source UNIQUE (skill_origin_id, source_version)
);

ALTER TABLE api_token ADD COLUMN token_kind VARCHAR(32) NOT NULL DEFAULT 'PERSONAL';
ALTER TABLE api_token ADD COLUMN client_id VARCHAR(128);
ALTER TABLE api_token ADD COLUMN client_name VARCHAR(128);

CREATE TABLE api_token_namespace_grant (
    token_id BIGINT NOT NULL REFERENCES api_token(id) ON DELETE CASCADE,
    namespace_id BIGINT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
    PRIMARY KEY (token_id, namespace_id)
);

CREATE INDEX idx_skill_origin_provider_slug ON skill_origin(provider, source_slug);
CREATE INDEX idx_skill_version_provenance_sha ON skill_version_provenance(package_sha256);
CREATE INDEX idx_api_token_namespace_grant_namespace ON api_token_namespace_grant(namespace_id);

UPDATE api_token
SET scope_json = (
    SELECT COALESCE(jsonb_agg(value), '[]'::jsonb)::text
    FROM jsonb_array_elements_text(api_token.scope_json::jsonb) AS value
    WHERE value <> 'skill:delete'
)
WHERE scope_json::jsonb ? 'skill:delete';
