package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.service.AuditRequestContext;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.ws.Client;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Receives Feishu review card actions over a WebSocket long connection instead
 * of an inbound HTTP webhook. Suited to internal-only deployments where Feishu's
 * servers cannot reach the backend: the backend dials out to Feishu, so it only
 * needs outbound public network access.
 *
 * <p>Only active when the bot is enabled. On {@link ApplicationReadyEvent} it
 * opens the connection in a background thread and registers a
 * {@code card.action.trigger} handler that delegates to
 * {@link FeishuCardActionDispatcher} (shared review-decision logic), then maps
 * the result back into Feishu's toast / card-replace response schema.
 *
 * <p><b>Deployment note:</b> long connections are cluster-mode — if the backend
 * is scaled to multiple replicas, only one random instance receives each event.
 * The current deployment is single-replica, so this is not an issue today.
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuLongConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionListener.class);

    private final FeishuBotProperties properties;
    private final FeishuCardActionDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    private volatile Client client;

    public FeishuLongConnectionListener(FeishuBotProperties properties,
                                        FeishuCardActionDispatcher dispatcher,
                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async("skillhubEventExecutor")
    public void startAfterApplicationReady() {
        if (properties.getAppId() == null || properties.getAppId().isBlank()
                || properties.getAppSecret() == null || properties.getAppSecret().isBlank()) {
            log.warn("Feishu long connection not started: app-id/app-secret not configured");
            return;
        }
        try {
            EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                    .onP2CardActionTrigger(cardActionHandler())
                    .build();
            Client ws = new Client.Builder(properties.getAppId(), properties.getAppSecret())
                    .eventHandler(eventDispatcher)
                    .build();
            this.client = ws;
            log.info("Starting Feishu long connection for card callbacks");
            // Client.start() blocks maintaining the connection; this method runs on the
            // async event executor so it does not hold up application startup.
            ws.start();
        } catch (Exception e) {
            log.error("Failed to start Feishu long connection: {}", e.getMessage(), e);
        }
    }

    private P2CardActionTriggerHandler cardActionHandler() {
        return new P2CardActionTriggerHandler() {
            @Override
            public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                return FeishuLongConnectionListener.this.handleCardAction(event);
            }
        };
    }

    P2CardActionTriggerResponse handleCardAction(P2CardActionTrigger event) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        P2CardActionTriggerData data = event != null ? event.getEvent() : null;
        if (data == null || data.getAction() == null) {
            return withToast(response, "info", "无法识别的操作");
        }

        CallBackAction action = data.getAction();
        Map<String, Object> value = action.getValue();
        String actionCode = value != null ? asString(value.get("action")) : null;
        Long reviewTaskId = parseLong(value != null ? asString(value.get("reviewTaskId")) : null);
        String openId = data.getOperator() != null ? data.getOperator().getOpenId() : null;
        String reason = extractReason(action);

        FeishuCardActionDispatcher.Result result = dispatcher.dispatch(
                actionCode, reviewTaskId, openId, reason,
                new AuditRequestContext(null, "feishu-long-connection"));

        if (result.isCardReplace()) {
            CallBackCard card = new CallBackCard();
            card.setType("raw");
            card.setData(parseCardData(result.cardJson()));
            response.setCard(card);
            return response;
        }
        return withToast(response, result.toastType(), result.toastContent());
    }

    private Object parseCardData(String cardJson) {
        try {
            return objectMapper.readValue(cardJson, Map.class);
        } catch (Exception e) {
            throw new FeishuBotException("Failed to parse card replacement JSON", e);
        }
    }

    /** Reads the rejection reason from the several shapes Feishu may use. */
    private String extractReason(CallBackAction action) {
        Map<String, Object> formValue = action.getFormValue();
        if (formValue != null) {
            String r = asString(formValue.get(ReviewCardFactory.REASON_INPUT_NAME));
            if (r != null) {
                return r;
            }
        }
        if (action.getInputValue() != null && !action.getInputValue().isBlank()) {
            return action.getInputValue();
        }
        Map<String, Object> value = action.getValue();
        return value != null ? asString(value.get(ReviewCardFactory.REASON_INPUT_NAME)) : null;
    }

    private static P2CardActionTriggerResponse withToast(P2CardActionTriggerResponse response,
                                                         String type, String content) {
        CallBackToast toast = new CallBackToast();
        toast.setType(type);
        toast.setContent(content);
        response.setToast(toast);
        return response;
    }

    private static String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PreDestroy
    public void stop() {
        Client current = this.client;
        if (current != null) {
            try {
                // Client exposes no public close(); rely on JVM shutdown to tear down
                // the daemon connection. Logged for operational visibility.
                log.info("Application shutting down; Feishu long connection will be closed");
            } catch (RuntimeException e) {
                log.debug("Error while stopping Feishu long connection", e);
            }
        }
    }
}
