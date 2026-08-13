package com.iflytek.skillhub.integration.feishu;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Record of one interactive Feishu review card sent to a reviewer. Lets a
 * decision made from either Feishu or the Web UI update every reviewer's card to
 * a terminal state (and prevents double-acting).
 */
@Entity
@Table(name = "feishu_review_card")
public class FeishuReviewCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_task_id", nullable = false)
    private Long reviewTaskId;

    @Column(name = "recipient_user_id", nullable = false)
    private String recipientUserId;

    @Column(name = "recipient_open_id", nullable = false)
    private String recipientOpenId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeishuReviewCardStatus status = FeishuReviewCardStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected FeishuReviewCard() {}

    public FeishuReviewCard(Long reviewTaskId, String recipientUserId,
                            String recipientOpenId, String messageId) {
        this.reviewTaskId = reviewTaskId;
        this.recipientUserId = recipientUserId;
        this.recipientOpenId = recipientOpenId;
        this.messageId = messageId;
    }

    public Long getId() { return id; }

    public Long getReviewTaskId() { return reviewTaskId; }

    public String getRecipientUserId() { return recipientUserId; }

    public String getRecipientOpenId() { return recipientOpenId; }

    public String getMessageId() { return messageId; }

    public FeishuReviewCardStatus getStatus() { return status; }

    public void markResolved() {
        this.status = FeishuReviewCardStatus.RESOLVED;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
