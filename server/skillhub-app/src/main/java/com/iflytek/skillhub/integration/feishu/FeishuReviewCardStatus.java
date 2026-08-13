package com.iflytek.skillhub.integration.feishu;

/**
 * Lifecycle of a {@link FeishuReviewCard}.
 *
 * <p>{@code PENDING} — the card is still actionable; {@code RESOLVED} — the
 * underlying review has been decided and the card has been (or is being) patched
 * to its terminal state.
 */
public enum FeishuReviewCardStatus {
    PENDING,
    RESOLVED
}
