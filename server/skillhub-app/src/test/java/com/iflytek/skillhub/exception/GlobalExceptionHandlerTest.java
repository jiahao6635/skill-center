package com.iflytek.skillhub.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private SensitiveLogSanitizer sensitiveLogSanitizer;

    @Mock
    private SkillHubMetrics metrics;

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("error.request.timeout", java.util.Locale.getDefault(), "Request timed out");
        messageSource.addMessage(
                "error.skill.publish.packageTooLarge",
                java.util.Locale.getDefault(),
                "Skill package exceeds the 100MB size limit. Reduce the ZIP and try again."
        );
        messageSource.addMessage("error.badRequest", java.util.Locale.getDefault(), "Invalid request");
        ApiResponseFactory responseFactory = new ApiResponseFactory(
                messageSource,
                Clock.fixed(Instant.parse("2026-03-20T00:00:00Z"), ZoneOffset.UTC)
        );
        handler = new GlobalExceptionHandler(responseFactory, sensitiveLogSanitizer, metrics);
    }

    @Test
    void handleAsyncRequestTimeout_shouldReturnNoContentForSseRequests() {
        when(request.getRequestURI()).thenReturn("/api/v1/notifications/sse");

        ResponseEntity<?> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void handleAsyncRequestTimeout_shouldReturnApiEnvelopeForNonSseRequests() {
        when(request.getRequestURI()).thenReturn("/api/v1/publish");
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/publish");

        ResponseEntity<?> response = handler.handleAsyncRequestTimeout(new AsyncRequestTimeoutException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.code()).isEqualTo(408);
        assertThat(body.msg()).isEqualTo("Request timed out");
    }

    @Test
    void handleSessionInvalidated_shouldReturn401ForSessionException() {
        when(request.getMethod()).thenReturn("GET");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn("/api/v1/skills");

        IllegalStateException ex = new IllegalStateException("Session was invalidated");
        ResponseEntity<ApiResponse<Void>> response = handler.handleSessionInvalidated(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(401);
    }

    @Test
    void handleSessionInvalidated_shouldRethrowNonSessionException() {
        IllegalStateException ex = new IllegalStateException("Some other error");

        assertThatThrownBy(() -> handler.handleSessionInvalidated(ex, request))
                .isSameAs(ex);
    }

    @Test
    void handleMaxUploadSize_shouldReturn413() {
        stubRequest("/api/web/skills/global/publish");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(104857600L),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(413);
        assertThat(response.getBody().msg()).contains("100MB");
    }

    @Test
    void handleBadRequest_shouldReturn413ForPackageTooLarge() {
        stubRequest("/api/web/skills/global/publish");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new IllegalArgumentException("Package too large: 184191181 bytes (max: 104857600)"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(413);
        assertThat(response.getBody().msg()).contains("100MB");
    }

    @Test
    void handleBadRequest_shouldKeepGeneric400ForOtherArguments() {
        stubRequest("/api/web/skills/global/publish");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new IllegalArgumentException("Package entry path is empty"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().msg()).isEqualTo("Invalid request");
    }

    private void stubRequest(String path) {
        when(request.getMethod()).thenReturn("POST");
        when(sensitiveLogSanitizer.sanitizeRequestTarget(request)).thenReturn(path);
    }
}
