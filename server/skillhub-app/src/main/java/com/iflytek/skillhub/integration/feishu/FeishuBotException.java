package com.iflytek.skillhub.integration.feishu;

/**
 * Raised when a Feishu Open API call fails at the transport or API level.
 *
 * <p>Kept unchecked so async listeners can log-and-continue; Feishu delivery is
 * a best-effort side channel and must never fail the core review workflow.
 */
public class FeishuBotException extends RuntimeException {

    public FeishuBotException(String message) {
        super(message);
    }

    public FeishuBotException(String message, Throwable cause) {
        super(message, cause);
    }
}
