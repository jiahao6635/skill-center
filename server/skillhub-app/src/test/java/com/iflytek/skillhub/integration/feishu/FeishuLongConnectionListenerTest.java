package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the WebSocket long-connection card-action parsing and its
 * mapping to Feishu's toast / card-replace response schema. The review-decision
 * logic itself lives in (and is tested via) {@link FeishuCardActionDispatcher}.
 */
@ExtendWith(MockitoExtension.class)
class FeishuLongConnectionListenerTest {

    @Mock FeishuBotProperties properties;
    @Mock FeishuCardActionDispatcher dispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FeishuLongConnectionListener listener() {
        return new FeishuLongConnectionListener(properties, dispatcher, objectMapper);
    }

    private P2CardActionTrigger trigger(Map<String, Object> value, String openId, String inputValue) {
        CallBackAction action = new CallBackAction();
        action.setValue(value);
        if (inputValue != null) {
            action.setInputValue(inputValue);
        }
        CallBackOperator operator = new CallBackOperator();
        operator.setOpenId(openId);
        P2CardActionTriggerData data = new P2CardActionTriggerData();
        data.setAction(action);
        data.setOperator(operator);
        P2CardActionTrigger event = new P2CardActionTrigger();
        event.setEvent(data);
        return event;
    }

    @Test
    void approve_parsesFieldsAndReturnsToast() {
        when(dispatcher.dispatch(eq("approve"), eq(42L), eq("ou_x"), any(), any()))
                .thenReturn(FeishuCardActionDispatcher.Result.toast("success", "已通过"));

        P2CardActionTriggerResponse resp = listener().handleCardAction(
                trigger(Map.of("action", "approve", "reviewTaskId", "42"), "ou_x", null));

        assertNotNull(resp.getToast());
        assertEquals("success", resp.getToast().getType());
        assertEquals("已通过", resp.getToast().getContent());
        assertNull(resp.getCard());
    }

    @Test
    void rejectSubmit_passesInputValueAsReason() {
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        when(dispatcher.dispatch(eq("reject_submit"), eq(42L), eq("ou_x"), reason.capture(), any()))
                .thenReturn(FeishuCardActionDispatcher.Result.toast("success", "已驳回"));

        listener().handleCardAction(
                trigger(Map.of("action", "reject_submit", "reviewTaskId", "42"), "ou_x", "缺少文档"));

        assertEquals("缺少文档", reason.getValue());
    }

    @Test
    void cardReplaceResult_mapsToRawCard() {
        when(dispatcher.dispatch(any(), any(), any(), any(), any()))
                .thenReturn(FeishuCardActionDispatcher.Result.cardReplace("{\"schema\":\"2.0\"}"));

        P2CardActionTriggerResponse resp = listener().handleCardAction(
                trigger(Map.of("action", "reject_prompt", "reviewTaskId", "42"), "ou_x", null));

        assertNotNull(resp.getCard());
        assertEquals("raw", resp.getCard().getType());
        assertNull(resp.getToast());
    }

    @Test
    void missingAction_returnsInfoToastWithoutDispatch() {
        P2CardActionTrigger event = new P2CardActionTrigger();
        event.setEvent(new P2CardActionTriggerData()); // no action

        P2CardActionTriggerResponse resp = listener().handleCardAction(event);

        assertNotNull(resp.getToast());
        assertEquals("info", resp.getToast().getType());
    }
}
