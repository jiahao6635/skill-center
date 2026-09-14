CREATE TABLE skill_invocation_event (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    email VARCHAR(256) NOT NULL,
    session_id VARCHAR(256) NOT NULL,
    skill_name VARCHAR(512) NOT NULL,
    client_product VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    time_source VARCHAR(16) NOT NULL,
    evidence VARCHAR(32) NOT NULL,
    center_user_id VARCHAR(128) REFERENCES user_account(id) ON DELETE SET NULL,
    center_skill_id BIGINT REFERENCES skill(id) ON DELETE SET NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(source,event_id)
);
COMMENT ON TABLE skill_invocation_event IS 'Agent Skill trigger events; independent of registry downloads. Unknown users/skills retain original metadata.';
COMMENT ON COLUMN skill_invocation_event.event_id IS 'Stable client invocation ID; retransmissions can enrich evidence without inserting another invocation.';
CREATE INDEX idx_invocation_time ON skill_invocation_event(occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_email_time ON skill_invocation_event(email,occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_user_time ON skill_invocation_event(center_user_id,occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_skill_time ON skill_invocation_event(center_skill_id,occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_name_time ON skill_invocation_event(skill_name,occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_product_time ON skill_invocation_event(client_product,occurred_at DESC,id DESC);
CREATE INDEX idx_invocation_session ON skill_invocation_event(session_id,occurred_at DESC,id DESC);
