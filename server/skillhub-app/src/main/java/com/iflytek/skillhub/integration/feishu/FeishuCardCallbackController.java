package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.service.AuditRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Receives Feishu card action callbacks ({@code card.action.trigger}) so
 * reviewers can approve/reject inside Feishu.
 *
 * <p>Public endpoint (permitAll + CSRF-ignored): authenticity is established by
 * the Feishu verification token and optional payload encryption, not a session.
 * Responses use Feishu's raw callback schema (challenge echo / toast / in-place
 * card update), so this controller does NOT use the app's {@code ApiResponse}
 * envelope.
 */
@RestController
@RequestMapping("/api/feishu")
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuCardCallbackController {

    private static final Logger log = LoggerFactory.getLogger(FeishuCardCallbackController.class);

    private final FeishuCallbackVerifier verifier;
    private final ReviewFeishuActionService actionService;
    private final FeishuReviewMessenger messenger;
    private final ReviewCardFactory cardFactory;
    private final ObjectMapper objectMapper;

    public FeishuCardCallbackController(FeishuCallbackVerifier verifier,
                                        ReviewFeishuActionService actionService,
                                        FeishuReviewMessenger messenger,
                                        ReviewCardFactory cardFactory,
                                        ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.actionService = actionService;
        this.messenger = messenger;
        this.cardFactory = cardFactory;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/card-callback")
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode rawBody, HttpServletRequest request) {
        JsonNode payload = verifier.decrypt(rawBody);

        // 1) URL verification handshake (sent when configuring the callback URL).
        JsonNode type = payload.get("type");
        if (type != null && "url_verification".equals(type.asText())) {
            ObjectNode challenge = objectMapper.createObjectNode();
            challenge.put("challenge", text(payload, "challenge"));
            return ResponseEntity.ok(challenge);
        }

        // 2) Authenticity.
        if (!verifier.verifyToken(payload)) {
            log.warn("Rejected Feishu callback with invalid verification token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 3) Card action.
        JsonNode action = payload.get("action");
        if (action == null) {
            return ResponseEntity.ok(objectMapper.createObjectNode());
        }
        JsonNode value = action.get("value");
        String actionCode = value != null ? text(value, "action") : null;
        Long reviewTaskId = parseLong(value != null ? text(value, "reviewTaskId") : null);
        String openId = resolveOpenId(payload);

        if (actionCode == null || reviewTaskId == null) {
            return ResponseEntity.ok(toast("info", "无法识别的操作"));
        }

        // Reject prompt: no decision yet — return the reason-input card in place.
        if (ReviewCardFactory.ACTION_REJECT_PROMPT.equals(actionCode)) {
            ReviewCardContext ctx = messenger.buildContext(reviewTaskId);
            if (ctx == null) {
                return ResponseEntity.ok(toast("error", "审核任务不存在"));
            }
            return ResponseEntity.ok(cardReplace(cardFactory.reasonInputCard(ctx)));
        }

        Optional<String> userId = openId != null ? actionService.resolveUserId(openId) : Optional.empty();
        if (userId.isEmpty()) {
            return ResponseEntity.ok(toast("error", "未绑定飞书账号，请先用飞书登录 Skill Center"));
        }

        AuditRequestContext audit = AuditRequestContext.from(request);
        try {
            if (ReviewCardFactory.ACTION_APPROVE.equals(actionCode)) {
                actionService.approve(reviewTaskId, userId.get(), audit);
                return ResponseEntity.ok(toast("success", "已通过"));
            }
            if (ReviewCardFactory.ACTION_REJECT_SUBMIT.equals(actionCode)) {
                String reason = extractReason(action);
                actionService.reject(reviewTaskId, userId.get(), reason, audit);
                return ResponseEntity.ok(toast("success", "已驳回"));
            }
            return ResponseEntity.ok(toast("info", "无法识别的操作"));
        } catch (DomainBadRequestException e) {
            // Most commonly review.not_pending — already decided via Web or another reviewer.
            return ResponseEntity.ok(toast("info", "该审核已处理"));
        } catch (RuntimeException e) {
            log.warn("Feishu review action failed for review {}", reviewTaskId, e);
            return ResponseEntity.ok(toast("error", "操作失败，请稍后重试或前往 Web 端处理"));
        }
    }

    // --- payload helpers ---

    private String resolveOpenId(JsonNode payload) {
        // Card 2.0 nests the operator under operator.open_id; older shapes use open_id.
        JsonNode operator = payload.get("operator");
        if (operator != null) {
            String nested = text(operator, "open_id");
            if (nested != null) {
                return nested;
            }
        }
        return text(payload, "open_id");
    }

    /** Reads the rejection reason from the several shapes Feishu may use. */
    private String extractReason(JsonNode action) {
        JsonNode formValue = action.get("form_value");
        if (formValue != null) {
            String r = text(formValue, ReviewCardFactory.REASON_INPUT_NAME);
            if (r != null) {
                return r;
            }
        }
        String inputValue = text(action, "input_value");
        if (inputValue != null) {
            return inputValue;
        }
        JsonNode value = action.get("value");
        return value != null ? text(value, ReviewCardFactory.REASON_INPUT_NAME) : null;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Feishu callback response builders ---

    private ObjectNode toast(String type, String content) {
        ObjectNode toast = objectMapper.createObjectNode();
        ObjectNode inner = toast.putObject("toast");
        inner.put("type", type);
        inner.put("content", content);
        return toast;
    }

    private ObjectNode cardReplace(String cardJson) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            ObjectNode card = response.putObject("card");
            card.put("type", "raw");
            card.set("data", objectMapper.readTree(cardJson));
            return response;
        } catch (Exception e) {
            throw new FeishuBotException("Failed to build card replacement response", e);
        }
    }
}
