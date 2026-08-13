-- Tracks interactive Feishu review cards sent to reviewers so decisions made in
-- either Feishu or the Web UI can update every reviewer's card to a terminal state.
CREATE TABLE feishu_review_card (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_task_id    BIGINT       NOT NULL,
    recipient_user_id VARCHAR(255) NOT NULL,
    recipient_open_id VARCHAR(256) NOT NULL,
    message_id        VARCHAR(256) NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_feishu_review_card_review_task_id ON feishu_review_card (review_task_id);
