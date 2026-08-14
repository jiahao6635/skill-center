package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.domain.event.ReviewApprovedEvent;
import com.iflytek.skillhub.domain.event.ReviewRejectedEvent;
import com.iflytek.skillhub.domain.event.ReviewSubmittedEvent;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.namespace.NamespaceType;
import com.iflytek.skillhub.listener.RecipientResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bridges review domain events to Feishu interactive cards. Runs only when the
 * bot is enabled; otherwise these beans are absent and the in-app notification
 * path in {@code NotificationEventListener} is unaffected.
 *
 * <ul>
 *   <li>{@link ReviewSubmittedEvent} → send an action card to each eligible
 *       reviewer (namespace admins for TEAM namespaces, platform skill admins
 *       for GLOBAL), matching {@code ReviewPermissionChecker} semantics.</li>
 *   <li>{@link ReviewApprovedEvent}/{@link ReviewRejectedEvent} → patch all of
 *       that review's cards to a terminal state, covering both Feishu-side and
 *       Web-side decisions.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuReviewListener {

    private static final Logger log = LoggerFactory.getLogger(FeishuReviewListener.class);

    private final FeishuReviewMessenger messenger;
    private final RecipientResolver recipientResolver;
    private final NamespaceRepository namespaceRepository;

    public FeishuReviewListener(FeishuReviewMessenger messenger,
                                RecipientResolver recipientResolver,
                                NamespaceRepository namespaceRepository) {
        this.messenger = messenger;
        this.recipientResolver = recipientResolver;
        this.namespaceRepository = namespaceRepository;
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener
    public void onReviewSubmitted(ReviewSubmittedEvent event) {
        ReviewCardContext ctx = messenger.buildContext(event.reviewId());
        if (ctx == null) {
            return;
        }
        for (String reviewer : resolveReviewers(event.namespaceId())) {
            messenger.sendActionCard(reviewer, ctx);
        }
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener
    public void onReviewApproved(ReviewApprovedEvent event) {
        messenger.resolveCards(event.reviewId(), true, event.reviewerId(), null, false);
    }

    @Async("skillhubEventExecutor")
    @TransactionalEventListener
    public void onReviewRejected(ReviewRejectedEvent event) {
        messenger.resolveCards(event.reviewId(), false, event.reviewerId(), event.reason(), false);
    }

    /**
     * Feishu 通知范围：仅 TEAM 命名空间的技能，且只发给该命名空间的 OWNER/ADMIN。
     * GLOBAL 命名空间（以及查不到命名空间的情况）一律不发；平台管理员也不再发。
     */
    private Set<String> resolveReviewers(Long namespaceId) {
        Namespace namespace = namespaceRepository.findById(namespaceId).orElse(null);
        if (namespace == null || namespace.getType() != NamespaceType.TEAM) {
            return Set.of();
        }
        return new LinkedHashSet<>(recipientResolver.resolveNamespaceAdmins(namespaceId));
    }
}
