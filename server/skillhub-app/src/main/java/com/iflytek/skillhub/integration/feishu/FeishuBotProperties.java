package com.iflytek.skillhub.integration.feishu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the outbound Feishu (Lark) bot integration used
 * by the review workflow.
 *
 * <p>This is distinct from {@code skillhub.auth.feishu} (OAuth login). It powers
 * sending interactive review cards to reviewers and receiving their card action
 * callbacks. It reuses the same Feishu self-built app credentials as OAuth login
 * by default, but is toggled and configured independently so the bot can be
 * enabled/disabled without affecting login.
 */
@ConfigurationProperties(prefix = "skillhub.integration.feishu")
public class FeishuBotProperties {

    private boolean enabled = false;

    /** Feishu app ID from the developer console (defaults to the OAuth app id). */
    private String appId;

    /** Feishu app secret from the developer console (defaults to the OAuth app secret). */
    private String appSecret;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }
}
