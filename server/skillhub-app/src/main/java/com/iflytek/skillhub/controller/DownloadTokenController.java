package com.iflytek.skillhub.controller;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Issues short-lived API tokens for QoderWork deep link downloads.
 *
 * <p>When a user clicks "Open in QoderWork" the frontend calls this endpoint
 * to obtain a 10-minute download token. The token is embedded in the deep link
 * config so QoderWork can authenticate its download request.
 */
@RestController
@RequestMapping("/api/web/auth")
public class DownloadTokenController extends BaseApiController {

    private static final String TOKEN_NAME = "Deep Link Download";
    private static final String SCOPE_JSON = "[\"skill:read\"]";
    private static final long TOKEN_TTL_MINUTES = 10;

    private final ApiTokenService apiTokenService;

    public DownloadTokenController(ApiResponseFactory responseFactory,
                                   ApiTokenService apiTokenService) {
        super(responseFactory);
        this.apiTokenService = apiTokenService;
    }

    @PostMapping("/download-token")
    public ApiResponse<DownloadTokenResponse> createDownloadToken(
            @AuthenticationPrincipal PlatformPrincipal principal) {
        String expiresAt = Instant.now()
                .plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES)
                .toString();

        var result = apiTokenService.rotateToken(
                principal.userId(), TOKEN_NAME, SCOPE_JSON, expiresAt);

        return ok("response.success.created",
                new DownloadTokenResponse(result.rawToken(), expiresAt));
    }

    public record DownloadTokenResponse(String token, String expiresAt) {}
}
