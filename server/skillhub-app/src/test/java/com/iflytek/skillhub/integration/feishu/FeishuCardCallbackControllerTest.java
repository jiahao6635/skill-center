package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import com.iflytek.skillhub.service.AuditRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeishuCardCallbackControllerTest {

    @Mock FeishuCallbackVerifier verifier;
    @Mock ReviewFeishuActionService actionService;
    @Mock FeishuReviewMessenger messenger;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReviewCardFactory cardFactory;
    private FeishuCardCallbackController controller;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        cardFactory = new ReviewCardFactory(objectMapper);
        controller = new FeishuCardCallbackController(verifier, actionService, messenger, cardFactory, objectMapper);
        request = new MockHttpServletRequest();
    }

    private JsonNode json(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void urlVerification_echoesChallenge() {
        JsonNode body = json("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}");
        when(verifier.decrypt(body)).thenReturn(body);

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("challenge").asText()).isEqualTo("abc123");
        verifyNoInteractions(actionService);
    }

    @Test
    void invalidToken_returnsUnauthorized() {
        JsonNode body = json("{\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"approve\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(false);

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(actionService);
    }

    @Test
    void approve_invokesApprove_andReturnsToast() {
        JsonNode body = json("{\"operator\":{\"open_id\":\"ou_1\"},"
                + "\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"approve\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(true);
        when(actionService.resolveUserId("ou_1")).thenReturn(Optional.of("u1"));

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        verify(actionService).approve(eq(7L), eq("u1"), any(AuditRequestContext.class));
        assertThat(response.getBody().get("toast").get("type").asText()).isEqualTo("success");
    }

    @Test
    void rejectPrompt_returnsReasonInputCard_withoutDeciding() {
        JsonNode body = json("{\"operator\":{\"open_id\":\"ou_1\"},"
                + "\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"reject_prompt\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(true);
        when(messenger.buildContext(7L))
                .thenReturn(new ReviewCardContext(7L, "S", "t", "1.0.0", "alice", null));

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        assertThat(response.getBody().get("card")).isNotNull();
        verify(actionService, never()).reject(anyLong(), anyString(), any(), any());
    }

    @Test
    void rejectSubmit_passesReason() {
        JsonNode body = json("{\"operator\":{\"open_id\":\"ou_1\"},"
                + "\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"reject_submit\"},"
                + "\"form_value\":{\"reject_reason\":\"missing docs\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(true);
        when(actionService.resolveUserId("ou_1")).thenReturn(Optional.of("u1"));

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        verify(actionService).reject(eq(7L), eq("u1"), eq("missing docs"), any(AuditRequestContext.class));
        assertThat(response.getBody().get("toast").get("type").asText()).isEqualTo("success");
    }

    @Test
    void alreadyDecided_returnsInfoToast() {
        JsonNode body = json("{\"operator\":{\"open_id\":\"ou_1\"},"
                + "\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"approve\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(true);
        when(actionService.resolveUserId("ou_1")).thenReturn(Optional.of("u1"));
        when(actionService.approve(eq(7L), eq("u1"), any()))
                .thenThrow(new DomainBadRequestException("review.not_pending", 7L));

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("toast").get("type").asText()).isEqualTo("info");
    }

    @Test
    void unboundUser_returnsErrorToast() {
        JsonNode body = json("{\"operator\":{\"open_id\":\"ou_x\"},"
                + "\"action\":{\"value\":{\"reviewTaskId\":\"7\",\"action\":\"approve\"}}}");
        when(verifier.decrypt(body)).thenReturn(body);
        when(verifier.verifyToken(body)).thenReturn(true);
        when(actionService.resolveUserId("ou_x")).thenReturn(Optional.empty());

        ResponseEntity<JsonNode> response = controller.handle(body, request);

        assertThat(response.getBody().get("toast").get("type").asText()).isEqualTo("error");
        verify(actionService, never()).approve(anyLong(), anyString(), any());
    }
}
