package com.iflytek.skillhub.integration.feishu;

import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.service.AuditRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Transport-agnostic dispatcher for a Feishu review card action.
 *
 * <p>Takes the already-parsed action fields (regardless of whether they arrived
 * via HTTP webhook or the WebSocket long connection) and applies the review
 * decision through {@link ReviewFeishuActionService}. Returns a {@link Result}
 * describing what the caller should send back to Feishu — either a toast or an
 * in-place card replacement — so each transport only maps that result into its
 * own wire format.
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuCardActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FeishuCardActionDispatcher.class);

    private final ReviewFeishuActionService actionService;
    private final FeishuReviewMessenger messenger;
    private final ReviewCardFactory cardFactory;

    public FeishuCardActionDispatcher(ReviewFeishuActionService actionService,
                                      FeishuReviewMessenger messenger,
                                      ReviewCardFactory cardFactory) {
        this.actionService = actionService;
        this.messenger = messenger;
        this.cardFactory = cardFactory;
    }

    /**
     * Applies a parsed card action.
     *
     * @param actionCode   one of {@link ReviewCardFactory}'s ACTION_* codes
     * @param reviewTaskId the review task the card refers to
     * @param openId       the acting reviewer's Feishu open_id (may be null)
     * @param reason       rejection reason for {@code reject_submit} (may be null)
     * @param audit        audit context for the decision
     */
    public Result dispatch(String actionCode, Long reviewTaskId, String openId,
                           String reason, AuditRequestContext audit) {
        if (actionCode == null || reviewTaskId == null) {
            return Result.toast("info", "无法识别的操作");
        }

        // Reject prompt: no decision yet — replace the card with the reason-input card.
        if (ReviewCardFactory.ACTION_REJECT_PROMPT.equals(actionCode)) {
            ReviewCardContext ctx = messenger.buildContext(reviewTaskId);
            if (ctx == null) {
                return Result.toast("error", "审核任务不存在");
            }
            return Result.cardReplace(cardFactory.reasonInputCard(ctx));
        }

        Optional<String> userId = openId != null ? actionService.resolveUserId(openId) : Optional.empty();
        if (userId.isEmpty()) {
            return Result.toast("error", "未绑定飞书账号，请先用飞书登录 Skill Center");
        }

        try {
            if (ReviewCardFactory.ACTION_APPROVE.equals(actionCode)) {
                actionService.approve(reviewTaskId, userId.get(), audit);
                return Result.toast("success", "已通过");
            }
            if (ReviewCardFactory.ACTION_REJECT_SUBMIT.equals(actionCode)) {
                actionService.reject(reviewTaskId, userId.get(), reason, audit);
                return Result.toast("success", "已驳回");
            }
            return Result.toast("info", "无法识别的操作");
        } catch (DomainBadRequestException e) {
            // Most commonly review.not_pending — already decided via Web or another reviewer.
            return Result.toast("info", "该审核已处理");
        } catch (RuntimeException e) {
            log.warn("Feishu review action failed for review {}", reviewTaskId, e);
            return Result.toast("error", "操作失败，请稍后重试或前往 Web 端处理");
        }
    }

    /**
     * Outcome of dispatching a card action: either a toast to show the user, or a
     * raw card JSON to replace the existing card in place.
     */
    public record Result(Kind kind, String toastType, String toastContent, String cardJson) {

        public enum Kind { TOAST, CARD_REPLACE }

        static Result toast(String type, String content) {
            return new Result(Kind.TOAST, type, content, null);
        }

        static Result cardReplace(String cardJson) {
            return new Result(Kind.CARD_REPLACE, null, null, cardJson);
        }

        public boolean isCardReplace() {
            return kind == Kind.CARD_REPLACE;
        }
    }
}
