package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin outbound client for the Feishu (Lark) IM Open API used by the review
 * workflow to send and update interactive cards.
 *
 * <p>Follows the same pattern as {@code FeishuOAuthService}: it wraps a
 * {@link RestClient} bound to the Feishu base URL and caches the short-lived
 * {@code tenant_access_token}. Only the two operations the review flow needs are
 * exposed: send an interactive card to a user (by {@code open_id}) and update a
 * previously sent card (by {@code message_id}).
 *
 * <p>All methods throw {@link FeishuBotException} on transport or API errors so
 * callers (async listeners) can log-and-continue without leaking Feishu wire
 * details.
 */
public class FeishuBotClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotClient.class);

    private static final String FEISHU_BASE_URL = "https://open.feishu.cn";
    private static final String TENANT_TOKEN_PATH = "/open-apis/auth/v3/tenant_access_token/internal";
    private static final String SEND_MESSAGE_PATH = "/open-apis/im/v1/messages";
    private static final String PATCH_MESSAGE_PATH = "/open-apis/im/v1/messages/{message_id}";

    private final FeishuBotProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** Cached tenant access token. */
    private volatile String cachedTenantAccessToken;
    /** Expiry timestamp of the cached tenant access token (epoch millis). */
    private volatile long tenantTokenExpiryAt;

    public FeishuBotClient(FeishuBotProperties properties,
                           RestClient.Builder restClientBuilder,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(FEISHU_BASE_URL)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Sends an interactive card to a user's private chat.
     *
     * @param openId  the recipient's Feishu {@code open_id}
     * @param cardJson the card content as a JSON string (Feishu card schema)
     * @return the {@code message_id} of the sent message, used later for updates
     */
    public String sendInteractiveCard(String openId, String cardJson) {
        Map<String, String> body = Map.of(
                "receive_id", openId,
                "msg_type", "interactive",
                "content", cardJson
        );

        SendMessageResponse response = restClient.post()
                .uri(uriBuilder -> uriBuilder.path(SEND_MESSAGE_PATH)
                        .queryParam("receive_id_type", "open_id")
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getTenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(SendMessageResponse.class);

        if (response == null || response.code() != 0 || response.data() == null) {
            String msg = response != null ? response.msg() : "null response";
            throw new FeishuBotException("Failed to send Feishu card: " + msg);
        }
        return response.data().messageId();
    }

    /**
     * Replaces the content of a previously sent interactive card.
     *
     * @param messageId the {@code message_id} returned by {@link #sendInteractiveCard}
     * @param cardJson  the new card content as a JSON string
     */
    public void updateCard(String messageId, String cardJson) {
        Map<String, String> body = Map.of("content", cardJson);

        BaseResponse response = restClient.patch()
                .uri(PATCH_MESSAGE_PATH, messageId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getTenantAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(BaseResponse.class);

        if (response == null || response.code() != 0) {
            String msg = response != null ? response.msg() : "null response";
            throw new FeishuBotException("Failed to update Feishu card: " + msg);
        }
    }

    /**
     * Obtains the tenant access token, using a cached value when still valid.
     */
    private String getTenantAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedTenantAccessToken != null && tenantTokenExpiryAt > now + 60_000) {
            return cachedTenantAccessToken;
        }

        log.debug("Fetching new Feishu tenant_access_token");
        Map<String, String> requestBody = Map.of(
                "app_id", properties.getAppId(),
                "app_secret", properties.getAppSecret()
        );

        TenantTokenResponse response = restClient.post()
                .uri(TENANT_TOKEN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(TenantTokenResponse.class);

        if (response == null || response.code() != 0 || response.tenantAccessToken() == null) {
            String msg = response != null ? response.msg() : "null response";
            throw new FeishuBotException("Failed to obtain Feishu tenant_access_token: " + msg);
        }

        cachedTenantAccessToken = response.tenantAccessToken();
        tenantTokenExpiryAt = now + (long) response.expire() * 1000;
        return cachedTenantAccessToken;
    }

    // --- Feishu API response records ---

    private record TenantTokenResponse(
            int code,
            String msg,
            @JsonProperty("tenant_access_token") String tenantAccessToken,
            int expire
    ) {}

    private record BaseResponse(int code, String msg) {}

    private record SendMessageResponse(int code, String msg, SendMessageData data) {}

    private record SendMessageData(@JsonProperty("message_id") String messageId) {}
}
