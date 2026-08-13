package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.auth.entity.IdentityBinding;
import com.iflytek.skillhub.auth.repository.IdentityBindingRepository;
import com.iflytek.skillhub.domain.namespace.Namespace;
import com.iflytek.skillhub.domain.namespace.NamespaceRepository;
import com.iflytek.skillhub.domain.review.ReviewTask;
import com.iflytek.skillhub.domain.review.ReviewTaskRepository;
import com.iflytek.skillhub.domain.skill.Skill;
import com.iflytek.skillhub.domain.skill.SkillRepository;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Central helper for the Feishu review integration: resolves a SkillHub user to
 * their Feishu {@code open_id}, assembles {@link ReviewCardContext} from
 * skill/version/namespace records, and sends/updates cards while recording
 * {@link FeishuReviewCard} rows.
 *
 * <p>Shared by the outbound listeners and the inbound callback so card
 * bookkeeping lives in one place. Only present when the bot is enabled.
 */
@Service
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuReviewMessenger {

    private static final Logger log = LoggerFactory.getLogger(FeishuReviewMessenger.class);
    private static final String FEISHU_PROVIDER = "feishu";

    private final FeishuBotClient botClient;
    private final ReviewCardFactory cardFactory;
    private final FeishuReviewCardRepository cardRepository;
    private final IdentityBindingRepository identityBindingRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillRepository skillRepository;
    private final NamespaceRepository namespaceRepository;
    private final UserAccountRepository userAccountRepository;
    private final String publicBaseUrl;

    public FeishuReviewMessenger(FeishuBotClient botClient,
                                 ReviewCardFactory cardFactory,
                                 FeishuReviewCardRepository cardRepository,
                                 IdentityBindingRepository identityBindingRepository,
                                 ReviewTaskRepository reviewTaskRepository,
                                 SkillVersionRepository skillVersionRepository,
                                 SkillRepository skillRepository,
                                 NamespaceRepository namespaceRepository,
                                 UserAccountRepository userAccountRepository,
                                 @Value("${skillhub.public.base-url:}") String publicBaseUrl) {
        this.botClient = botClient;
        this.cardFactory = cardFactory;
        this.cardRepository = cardRepository;
        this.identityBindingRepository = identityBindingRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillRepository = skillRepository;
        this.namespaceRepository = namespaceRepository;
        this.userAccountRepository = userAccountRepository;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Looks up the Feishu {@code open_id} bound to a SkillHub user, if any. */
    public Optional<String> findOpenId(String userId) {
        return identityBindingRepository.findByUserId(userId).stream()
                .filter(b -> FEISHU_PROVIDER.equals(b.getProviderCode()))
                .map(IdentityBinding::getSubject)
                .findFirst();
    }

    /**
     * Sends an actionable review card to a reviewer and records it. Silently
     * skips reviewers with no Feishu binding; logs and swallows send failures so
     * one bad recipient never blocks the rest or the core workflow.
     */
    public void sendActionCard(String reviewerUserId, ReviewCardContext ctx) {
        Optional<String> openId = findOpenId(reviewerUserId);
        if (openId.isEmpty()) {
            return;
        }
        try {
            String messageId = botClient.sendInteractiveCard(openId.get(), cardFactory.actionCard(ctx));
            cardRepository.save(new FeishuReviewCard(
                    ctx.reviewTaskId(), reviewerUserId, openId.get(), messageId));
        } catch (RuntimeException e) {
            log.warn("Failed to send Feishu review card to user {} for review {}",
                    reviewerUserId, ctx.reviewTaskId(), e);
        }
    }

    /**
     * Patches every pending card for a review to its terminal state after a
     * decision (from either Feishu or the Web UI).
     */
    public void resolveCards(Long reviewTaskId, boolean approved, String decidedByUserId,
                             String reason, boolean viaFeishu) {
        List<FeishuReviewCard> cards = cardRepository.findByReviewTaskId(reviewTaskId);
        if (cards.isEmpty()) {
            return;
        }
        ReviewCardContext ctx = buildContext(reviewTaskId);
        if (ctx == null) {
            return;
        }
        String decidedBy = displayName(decidedByUserId);
        String terminal = cardFactory.terminalCard(ctx, approved, decidedBy, reason, viaFeishu);
        for (FeishuReviewCard card : cards) {
            if (card.getStatus() == FeishuReviewCardStatus.RESOLVED) {
                continue;
            }
            try {
                botClient.updateCard(card.getMessageId(), terminal);
            } catch (RuntimeException e) {
                log.warn("Failed to update Feishu card {} for review {}",
                        card.getMessageId(), reviewTaskId, e);
            }
            card.markResolved();
            cardRepository.save(card);
        }
    }

    /** Assembles the display context for a review task, or null if records are missing. */
    public ReviewCardContext buildContext(Long reviewTaskId) {
        ReviewTask task = reviewTaskRepository.findById(reviewTaskId).orElse(null);
        if (task == null) {
            return null;
        }
        SkillVersion version = skillVersionRepository.findById(task.getSkillVersionId()).orElse(null);
        Skill skill = version != null
                ? skillRepository.findById(version.getSkillId()).orElse(null)
                : null;
        Namespace namespace = namespaceRepository.findById(task.getNamespaceId()).orElse(null);

        String skillName = skill != null ? skillDisplayName(skill) : ("#" + task.getSkillVersionId());
        String namespaceSlug = namespace != null ? namespace.getSlug() : null;
        String versionLabel = version != null ? version.getVersion() : null;
        String submitter = displayName(task.getSubmittedBy());

        String reviewUrl = null;
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            reviewUrl = publicBaseUrl + "/reviews/" + reviewTaskId;
        }

        return new ReviewCardContext(reviewTaskId, skillName, namespaceSlug, versionLabel, submitter, reviewUrl);
    }

    private String skillDisplayName(Skill skill) {
        String name = skill.getDisplayName();
        return (name != null && !name.isBlank()) ? name : skill.getSlug();
    }

    private String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        return userAccountRepository.findById(userId)
                .map(UserAccount::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(userId);
    }
}
