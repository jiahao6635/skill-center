package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Feishu interactive-card JSON payloads for the three states of a
 * review card:
 * <ol>
 *   <li><b>action</b> — the initial card with 通过 / 驳回 buttons;</li>
 *   <li><b>reason input</b> — shown after 驳回 is tapped, collecting a reason;</li>
 *   <li><b>terminal</b> — the read-only card after a decision is made.</li>
 * </ol>
 *
 * <p>Button {@code value} maps encode the {@code reviewTaskId} and {@code action}
 * so the callback endpoint can route without server-side per-card state.
 */
@Component
public class ReviewCardFactory {

    /** Action codes carried in button values and parsed by the callback. */
    public static final String ACTION_APPROVE = "approve";
    public static final String ACTION_REJECT_PROMPT = "reject_prompt";
    public static final String ACTION_REJECT_SUBMIT = "reject_submit";

    /** Key of the reason input field inside the reject form. */
    public static final String REASON_INPUT_NAME = "reject_reason";

    private final ObjectMapper objectMapper;

    public ReviewCardFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** The initial actionable card sent to each reviewer. */
    public String actionCard(ReviewCardContext ctx) {
        Map<String, Object> card = baseCard("待审核技能", "blue", ctx);

        Map<String, Object> approveBtn = button("✅ 通过", "primary",
                Map.of("reviewTaskId", String.valueOf(ctx.reviewTaskId()), "action", ACTION_APPROVE));
        Map<String, Object> rejectBtn = button("❌ 驳回", "danger",
                Map.of("reviewTaskId", String.valueOf(ctx.reviewTaskId()), "action", ACTION_REJECT_PROMPT));

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("tag", "action");
        actions.put("actions", List.of(approveBtn, rejectBtn));

        appendElement(card, actions);
        return toJson(card);
    }

    /** Card shown after 驳回 is tapped: an input box plus a submit button. */
    public String reasonInputCard(ReviewCardContext ctx) {
        Map<String, Object> card = baseCard("驳回原因", "orange", ctx);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tag", "input");
        input.put("name", REASON_INPUT_NAME);
        input.put("placeholder", Map.of("tag", "plain_text", "content", "请输入驳回原因"));
        input.put("value", Map.of(
                "reviewTaskId", String.valueOf(ctx.reviewTaskId()),
                "action", ACTION_REJECT_SUBMIT));
        // The confirm button of an input element carries the submitted form data.
        input.put("confirm", Map.of(
                "title", Map.of("tag", "plain_text", "content", "确认驳回"),
                "text", Map.of("tag", "plain_text", "content", "驳回后提交人需修改并重新提交")));

        appendElement(card, input);
        return toJson(card);
    }

    /**
     * Read-only terminal card after a decision.
     *
     * @param approved   whether the review was approved
     * @param decidedBy  display name / id of the decider
     * @param reason     rejection reason (only for rejections; may be null)
     * @param viaFeishu  whether the decision was made from Feishu (vs the Web UI)
     */
    public String terminalCard(ReviewCardContext ctx, boolean approved, String decidedBy,
                               String reason, boolean viaFeishu) {
        String title = approved ? "✅ 已通过" : "❌ 已驳回";
        String color = approved ? "green" : "red";
        Map<String, Object> card = baseCard(title, color, ctx);

        StringBuilder note = new StringBuilder();
        note.append(approved ? "已通过" : "已驳回");
        if (decidedBy != null && !decidedBy.isBlank()) {
            note.append(" · 处理人：").append(decidedBy);
        }
        note.append(viaFeishu ? " · 来自飞书" : " · 来自 Web");
        if (!approved && reason != null && !reason.isBlank()) {
            note.append("\n驳回原因：").append(reason);
        }
        appendElement(card, markdown(note.toString()));
        return toJson(card);
    }

    // --- helpers ---

    private Map<String, Object> baseCard(String headerTitle, String headerColor, ReviewCardContext ctx) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", headerColor);
        header.put("title", Map.of("tag", "plain_text", "content", headerTitle));

        StringBuilder body = new StringBuilder();
        body.append("**技能**：").append(safe(ctx.skillName()));
        if (ctx.namespaceSlug() != null) {
            body.append("  \n**命名空间**：@").append(ctx.namespaceSlug());
        }
        if (ctx.version() != null) {
            body.append("  \n**版本**：").append(ctx.version());
        }
        if (ctx.submitter() != null) {
            body.append("  \n**提交人**：").append(ctx.submitter());
        }

        List<Object> elements = new ArrayList<>();
        elements.add(markdown(body.toString()));
        if (ctx.reviewUrl() != null && !ctx.reviewUrl().isBlank()) {
            elements.add(markdown("[在 Skill Center 中查看](" + ctx.reviewUrl() + ")"));
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", header);
        card.put("elements", elements);
        return card;
    }

    private Map<String, Object> markdown(String content) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("tag", "markdown");
        element.put("content", content);
        return element;
    }

    private Map<String, Object> button(String text, String type, Map<String, String> value) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("tag", "button");
        button.put("text", Map.of("tag", "plain_text", "content", text));
        button.put("type", type);
        button.put("value", value);
        return button;
    }

    @SuppressWarnings("unchecked")
    private void appendElement(Map<String, Object> card, Map<String, Object> element) {
        ((List<Object>) card.get("elements")).add(element);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String toJson(Map<String, Object> card) {
        try {
            return objectMapper.writeValueAsString(card);
        } catch (JsonProcessingException e) {
            throw new FeishuBotException("Failed to serialize Feishu card", e);
        }
    }
}
