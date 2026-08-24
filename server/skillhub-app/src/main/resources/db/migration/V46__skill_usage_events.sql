CREATE TABLE skill_usage_event (
    id               BIGSERIAL PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL,
    action           VARCHAR(32)  NOT NULL,
    skill_id         BIGINT REFERENCES skill(id) ON DELETE SET NULL,
    skill_version_id BIGINT,
    namespace_id     BIGINT,
    actor_user_id    VARCHAR(128),
    actor_key        VARCHAR(160) NOT NULL,
    actor_kind       VARCHAR(16)  NOT NULL,
    client           VARCHAR(16)  NOT NULL,
    auth_method      VARCHAR(16)  NOT NULL,
    request_id       VARCHAR(64),
    client_ip        VARCHAR(64),
    user_agent       VARCHAR(512),
    dedup_key        VARCHAR(512),
    payload_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE skill_usage_event IS
  'Append-only skill usage ledger. Public download_count remains on skill. Unique counts live on skill_usage_actor.';
COMMENT ON COLUMN skill_usage_event.actor_key IS
  'user:{userId} (userId up to 128 chars) or anon:{hex}. Column is VARCHAR(160).';
COMMENT ON COLUMN skill_usage_event.actor_kind IS
  'USER | ANONYMOUS';
COMMENT ON COLUMN skill_usage_event.client IS
  'WEB | CLI | COMPAT | API_TOKEN | UNKNOWN';
COMMENT ON COLUMN skill_usage_event.auth_method IS
  'SESSION | API_TOKEN | ANONYMOUS | UNKNOWN';
COMMENT ON COLUMN skill_usage_event.dedup_key IS
  'Optional unique key inside the dedup window; UNIQUE where not null. VARCHAR(512): must fit action + subject + VARCHAR(160) actor_key + bucket.';

CREATE UNIQUE INDEX uq_skill_usage_event_dedup
    ON skill_usage_event (dedup_key)
    WHERE dedup_key IS NOT NULL;

CREATE INDEX idx_skill_usage_event_skill_time
    ON skill_usage_event (skill_id, occurred_at DESC)
    WHERE skill_id IS NOT NULL;

CREATE INDEX idx_skill_usage_event_action_time
    ON skill_usage_event (action, occurred_at DESC);

CREATE INDEX idx_skill_usage_event_actor_time
    ON skill_usage_event (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX idx_skill_usage_event_skill_action_actor_time
    ON skill_usage_event (skill_id, action, actor_key, occurred_at DESC);

CREATE INDEX idx_skill_usage_event_occurred_at
    ON skill_usage_event (occurred_at);

-- Lifetime first-seen / last-seen. Authority for unique_* rollup. Survives event DELETE.
CREATE TABLE skill_usage_actor (
    skill_id   BIGINT      NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    action     VARCHAR(32) NOT NULL,
    actor_key  VARCHAR(160) NOT NULL,
    first_at   TIMESTAMPTZ NOT NULL,
    last_at    TIMESTAMPTZ NOT NULL,
    last_client VARCHAR(16) NOT NULL,
    PRIMARY KEY (skill_id, action, actor_key)
);

CREATE INDEX idx_skill_usage_actor_skill_action_last
    ON skill_usage_actor (skill_id, action, last_at DESC, actor_key);

COMMENT ON TABLE skill_usage_actor IS
  'Lifetime unique authority for DOWNLOAD and VIEW only. SEARCH is query-level (skill_id NULL) and never inserts here. UPLOAD/UPDATE/PUBLISH write events only.';

CREATE TABLE skill_usage_daily (
    skill_id      BIGINT      NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    day           DATE        NOT NULL,
    action        VARCHAR(32) NOT NULL,
    event_count   BIGINT      NOT NULL DEFAULT 0,
    unique_actors BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (skill_id, day, action)
);

CREATE INDEX idx_skill_usage_daily_day ON skill_usage_daily (day);

CREATE TABLE skill_usage_rollup (
    skill_id             BIGINT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
    view_count           BIGINT NOT NULL DEFAULT 0,
    unique_downloaders   BIGINT NOT NULL DEFAULT 0,
    unique_viewers       BIGINT NOT NULL DEFAULT 0,
    upload_count         BIGINT NOT NULL DEFAULT 0,
    update_count         BIGINT NOT NULL DEFAULT 0,
    last_downloaded_at   TIMESTAMPTZ,
    last_viewed_at       TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE skill_usage_job_state (
    job_name     VARCHAR(64) PRIMARY KEY,
    watermark    TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    locked_by    VARCHAR(128),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO skill_usage_job_state (job_name, updated_at) VALUES ('rollup', CURRENT_TIMESTAMP);
