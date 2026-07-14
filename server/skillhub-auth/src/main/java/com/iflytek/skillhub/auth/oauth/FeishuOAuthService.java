package com.iflytek.skillhub.auth.oauth;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the non-standard Feishu (Lark) OAuth2 login flow.
 *
 * <p>Feishu requires an intermediate {@code app_access_token} that must be obtained
 * before exchanging the authorization code for a user access token. This service
 * caches the app access token to avoid redundant API calls.
 *
 * <p>The flow:
 * <ol>
 *   <li>Redirect user to Feishu authorization URL (QR code page)</li>
 *   <li>Feishu redirects back with {@code code} and {@code state}</li>
 *   <li>Obtain {@code app_access_token} using app credentials</li>
 *   <li>Exchange {@code code} for {@code user_access_token}</li>
 *   <li>Fetch user info using {@code user_access_token}</li>
 *   <li>Delegate to {@link OAuthLoginFlowService} for access policy and identity binding</li>
 * </ol>
 */
public class FeishuOAuthService {

    private static final Logger log = LoggerFactory.getLogger(FeishuOAuthService.class);

    private static final String FEISHU_BASE_URL = "https://open.feishu.cn";
    private static final String AUTHORIZE_PATH = "/open-apis/authen/v1/authorize";
    private static final String APP_TOKEN_PATH = "/open-apis/auth/v3/app_access_token/internal";
    private static final String USER_TOKEN_PATH = "/open-apis/authen/v1/oidc/access_token";
    private static final String USER_INFO_PATH = "/open-apis/authen/v1/user_info";

    private static final String SESSION_STATE_ATTRIBUTE = "skillhub.feishu.oauth.state";
    private static final String CALLBACK_PATH = "/login/oauth2/feishu/callback";

    private final FeishuOAuthProperties properties;
    private final OAuthLoginFlowService oauthLoginFlowService;
    private final RestClient restClient;
    private final String publicBaseUrl;

    /** Cached app access token. */
    private volatile String cachedAppAccessToken;
    /** Expiry timestamp of the cached app access token (epoch millis). */
    private volatile long appTokenExpiryAt;

    public FeishuOAuthService(FeishuOAuthProperties properties,
                              OAuthLoginFlowService oauthLoginFlowService,
                              RestClient.Builder restClientBuilder,
                              String publicBaseUrl) {
        this.properties = properties;
        this.oauthLoginFlowService = oauthLoginFlowService;
        this.restClient = restClientBuilder
            .baseUrl(FEISHU_BASE_URL)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Builds the Feishu authorization URL and stores the CSRF state and returnTo
     * in the HTTP session.
     *
     * @param request the current HTTP request (used to build the redirect URI)
     * @return the full authorization URL to redirect the user to
     */
    public String buildAuthorizationUrl(HttpServletRequest request) {
        String state = UUID.randomUUID().toString();
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_STATE_ATTRIBUTE, state);

        // Reuse the existing returnTo storage so OAuthLoginFlowService.consumeReturnTo
        // can retrieve it after the callback.
        oauthLoginFlowService.rememberReturnTo(request);

        String redirectUri = buildRedirectUri(request);

        return UriComponentsBuilder
            .fromHttpUrl(FEISHU_BASE_URL + AUTHORIZE_PATH)
            .queryParam("app_id", properties.getAppId())
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .build()
            .toUriString();
    }

    /**
     * Handles the OAuth callback by verifying state, exchanging the code for tokens,
     * fetching user info, and provisioning a platform principal.
     *
     * @param code    the authorization code from Feishu
     * @param state   the state parameter from Feishu
     * @param session the HTTP session (must contain the stored state)
     * @return the authenticated platform principal
     * @throws OAuth2AuthenticationException if state validation fails or Feishu API returns an error
     */
    public PlatformPrincipal handleCallback(String code, String state, HttpSession session) {
        verifyState(session, state);

        String appAccessToken = getAppAccessToken();
        String userAccessToken = exchangeUserCode(appAccessToken, code);
        FeishuUserInfo userInfo = fetchUserInfo(userAccessToken);

        log.info("Feishu OAuth user info retrieved - open_id: {}, name: {}",
            userInfo.openId(), userInfo.name());

        OAuthClaims claims = toClaims(userInfo);
        return oauthLoginFlowService.authenticate(claims);
    }

    private void verifyState(HttpSession session, String state) {
        Object storedState = session.getAttribute(SESSION_STATE_ATTRIBUTE);
        session.removeAttribute(SESSION_STATE_ATTRIBUTE);

        if (storedState == null || !storedState.equals(state)) {
            log.warn("Feishu OAuth state mismatch - stored: {}, received: {}", storedState, state);
            throw new OAuth2AuthenticationException(
                new OAuth2Error("invalid_state", "Feishu OAuth state mismatch", null));
        }
    }

    private String buildRedirectUri(HttpServletRequest request) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            return publicBaseUrl + CALLBACK_PATH;
        }

        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();

        StringBuilder uri = new StringBuilder();
        uri.append(scheme).append("://").append(serverName);
        if (("http".equals(scheme) && serverPort != 80)
                || ("https".equals(scheme) && serverPort != 443)) {
            uri.append(":").append(serverPort);
        }
        uri.append(contextPath).append(CALLBACK_PATH);
        return uri.toString();
    }

    /**
     * Obtains the app access token, using a cached value when still valid.
     */
    private String getAppAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedAppAccessToken != null && appTokenExpiryAt > now + 60_000) {
            return cachedAppAccessToken;
        }

        log.debug("Fetching new Feishu app_access_token");

        Map<String, String> requestBody = Map.of(
            "app_id", properties.getAppId(),
            "app_secret", properties.getAppSecret()
        );

        AppTokenResponse response = restClient.post()
            .uri(APP_TOKEN_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(AppTokenResponse.class);

        if (response == null || response.code() != 0 || response.appAccessToken() == null) {
            String msg = response != null ? response.msg() : "null response";
            log.error("Failed to obtain Feishu app_access_token: code={}, msg={}",
                response != null ? response.code() : "null", msg);
            throw new OAuth2AuthenticationException(
                new OAuth2Error("feishu_app_token_error",
                    "Failed to obtain Feishu app access token: " + msg, null));
        }

        cachedAppAccessToken = response.appAccessToken();
        appTokenExpiryAt = now + (long) response.expire() * 1000;
        log.debug("Feishu app_access_token obtained, expires in {} seconds", response.expire());
        return cachedAppAccessToken;
    }

    /**
     * Exchanges the authorization code for a user access token.
     */
    private String exchangeUserCode(String appAccessToken, String code) {
        Map<String, String> requestBody = Map.of(
            "grant_type", "authorization_code",
            "code", code
        );

        UserTokenResponse response = restClient.post()
            .uri(USER_TOKEN_PATH)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + appAccessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(UserTokenResponse.class);

        if (response == null || response.code() != 0
                || response.data() == null || response.data().accessToken() == null) {
            String msg = response != null ? response.msg() : "null response";
            log.error("Failed to exchange Feishu user access token: code={}, msg={}",
                response != null ? response.code() : "null", msg);
            throw new OAuth2AuthenticationException(
                new OAuth2Error("feishu_user_token_error",
                    "Failed to exchange Feishu authorization code: " + msg, null));
        }

        log.debug("Feishu user access token obtained");
        return response.data().accessToken();
    }

    /**
     * Fetches the Feishu user info using the user access token.
     */
    private FeishuUserInfo fetchUserInfo(String userAccessToken) {
        FeishuUserInfoResponse response = restClient.get()
            .uri(USER_INFO_PATH)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken)
            .retrieve()
            .body(FeishuUserInfoResponse.class);

        if (response == null || response.code() != 0 || response.data() == null) {
            String msg = response != null ? response.msg() : "null response";
            log.error("Failed to fetch Feishu user info: code={}, msg={}",
                response != null ? response.code() : "null", msg);
            throw new OAuth2AuthenticationException(
                new OAuth2Error("feishu_userinfo_error",
                    "Failed to fetch Feishu user info: " + msg, null));
        }

        return response.data();
    }

    /**
     * Converts Feishu user info into normalized {@link OAuthClaims}.
     *
     * <p>When the Feishu user info includes an email, it is passed through as the
     * claims email with {@code emailVerified} set to {@code true}; otherwise both
     * are left as {@code null} and {@code false}.
     */
    private OAuthClaims toClaims(FeishuUserInfo userInfo) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("avatar_url", userInfo.avatarUrl());
        extra.put("en_name", userInfo.enName());
        extra.put("union_id", userInfo.unionId());
        extra.put("user_id", userInfo.userId());

        String email = userInfo.email() != null && !userInfo.email().isBlank()
            ? userInfo.email() : null;

        return new OAuthClaims(
            "feishu",
            userInfo.openId(),
            email,
            email != null,
            userInfo.name(),
            extra
        );
    }

    // --- Feishu API response records ---

    private record AppTokenResponse(
        int code,
        String msg,
        @JsonProperty("app_access_token") String appAccessToken,
        int expire
    ) {}

    private record UserTokenResponse(
        int code,
        String msg,
        UserTokenData data
    ) {}

    private record UserTokenData(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") int expiresIn
    ) {}

    private record FeishuUserInfoResponse(
        int code,
        String msg,
        FeishuUserInfo data
    ) {}

    private record FeishuUserInfo(
        String name,
        @JsonProperty("en_name") String enName,
        @JsonProperty("open_id") String openId,
        @JsonProperty("union_id") String unionId,
        @JsonProperty("user_id") String userId,
        String email,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("avatar_big") String avatarBig,
        @JsonProperty("avatar_middle") String avatarMiddle,
        @JsonProperty("avatar_thumb") String avatarThumb,
        @JsonProperty("tenant_key") String tenantKey
    ) {}
}
