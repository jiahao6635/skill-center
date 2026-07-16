ALTER TABLE audit_log
    ADD COLUMN auth_source VARCHAR(32),
    ADD COLUMN token_id BIGINT,
    ADD COLUMN token_prefix VARCHAR(16),
    ADD COLUMN client_name VARCHAR(128),
    ADD COLUMN authorized_namespace_id BIGINT;

ALTER TABLE idempotency_record
    ALTER COLUMN resource_type DROP NOT NULL,
    ALTER COLUMN request_id TYPE VARCHAR(255),
    ADD COLUMN actor_user_id VARCHAR(128),
    ADD COLUMN token_id BIGINT,
    ADD COLUMN http_method VARCHAR(16),
    ADD COLUMN request_path TEXT,
    ADD COLUMN request_digest VARCHAR(64),
    ADD COLUMN response_body TEXT;

CREATE INDEX idx_audit_log_token_id ON audit_log(token_id);
CREATE INDEX idx_audit_log_authorized_namespace ON audit_log(authorized_namespace_id);
