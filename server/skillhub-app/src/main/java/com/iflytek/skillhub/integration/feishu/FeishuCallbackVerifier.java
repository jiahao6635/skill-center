package com.iflytek.skillhub.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Verifies and, when configured, decrypts Feishu callback payloads.
 *
 * <p>When an encrypt key is set in the developer console, Feishu wraps the body
 * as {@code {"encrypt": "<base64>"}}. The plaintext is AES-256-CBC with the key
 * being {@code SHA-256(encryptKey)} and the IV being the first 16 bytes of the
 * decoded ciphertext (per Feishu's event/callback encryption spec).
 *
 * <p>Token verification compares the payload's {@code token} field against the
 * configured verification token.
 */
@Component
@ConditionalOnProperty(prefix = "skillhub.integration.feishu", name = "enabled", havingValue = "true")
public class FeishuCallbackVerifier {

    private final FeishuBotProperties properties;
    private final ObjectMapper objectMapper;

    public FeishuCallbackVerifier(FeishuBotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the effective payload JSON: the input unchanged when encryption is
     * not configured, or the decrypted body when the request carries
     * {@code encrypt}.
     *
     * @throws FeishuBotException if decryption fails
     */
    public JsonNode decrypt(JsonNode rawBody) {
        String encryptKey = properties.getEncryptKey();
        JsonNode encryptNode = rawBody.get("encrypt");
        if (encryptNode == null || encryptNode.isNull()) {
            return rawBody;
        }
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new FeishuBotException("Received encrypted Feishu payload but no encrypt key is configured");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptNode.asText());
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));
            byte[] iv = Arrays.copyOfRange(decoded, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(decoded, 16, decoded.length);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(cipherText);
            return objectMapper.readTree(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new FeishuBotException("Failed to decrypt Feishu callback payload", e);
        }
    }

    /**
     * Verifies the payload token against the configured verification token.
     * When no verification token is configured, verification is skipped
     * (returns true) so local/dev setups without a token still work.
     */
    public boolean verifyToken(JsonNode payload) {
        String expected = properties.getVerificationToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String actual = tokenOf(payload);
        return actual != null && constantTimeEquals(expected, actual);
    }

    /** Extracts the token from either the top level or the nested header (schema 2.0). */
    private String tokenOf(JsonNode payload) {
        JsonNode token = payload.get("token");
        if (token != null && !token.isNull()) {
            return token.asText();
        }
        JsonNode header = payload.get("header");
        if (header != null) {
            JsonNode headerToken = header.get("token");
            if (headerToken != null && !headerToken.isNull()) {
                return headerToken.asText();
            }
        }
        return null;
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
