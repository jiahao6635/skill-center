package com.iflytek.skillhub.auth.device;

import com.iflytek.skillhub.auth.token.ApiTokenService;
import com.iflytek.skillhub.domain.shared.exception.DomainBadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Implements the device authorization flow used by CLI-style clients.
 *
 * <p>State is stored in Redis so the browser authorization step and token
 * polling step can rendezvous without holding server-side session state.
 */
@Service
public class DeviceAuthService {

    private static final String DEVICE_CODE_PREFIX = "device:code:";
    private static final String DEVICE_CLAIM_PREFIX = "device:claim:";
    private static final String USER_CODE_PREFIX = "device:usercode:";
    private static final String DEVICE_KNOWN_PREFIX = "device:known:";
    private static final int EXPIRES_IN_SECONDS = 900;
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final String USER_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long PENDING_CODE_TTL_MINUTES = EXPIRES_IN_SECONDS / 60L;
    private static final long USED_CODE_TTL_MINUTES = 1L;
    private static final Set<String> ALLOWED_SCOPES = Set.of(
            "skill:read", "skill:publish", "skill:lifecycle", "review:read", "review:decide");

    private final RedisTemplate<String, Object> redisTemplate;
    private final ApiTokenService apiTokenService;
    private final String verificationUri;
    private final SecureRandom random = new SecureRandom();

    public DeviceAuthService(RedisTemplate<String, Object> redisTemplate,
                             ApiTokenService apiTokenService,
                             @Value("${skillhub.device-auth.verification-uri:/device}") String verificationUri) {
        this.redisTemplate = redisTemplate;
        this.apiTokenService = apiTokenService;
        this.verificationUri = verificationUri;
    }

    /**
     * Starts a new device flow and returns both the polling token and the
     * user-facing verification code.
     */
    public DeviceCodeResponse generateDeviceCode(String clientId, String clientName, List<String> scopes,
                                                 String requestedNamespaceSlug, Integer expiresInDays) {
        String deviceCode = generateRandomDeviceCode();
        String userCode = generateUserCode();

        List<String> requestedScopes = scopes == null || scopes.isEmpty()
                ? List.of("skill:read", "skill:publish") : scopes.stream().distinct().toList();
        if (!ALLOWED_SCOPES.containsAll(requestedScopes))
            throw new DomainBadRequestException("validation.token.scope.invalid");
        String resolvedClientId = StringUtils.hasText(clientId) ? clientId : generateRandomDeviceCode();
        String resolvedClientName = StringUtils.hasText(clientName) ? clientName.trim() : "SkillHub CLI";
        int resolvedExpiresInDays = expiresInDays == null ? 90 : expiresInDays;
        if (resolvedExpiresInDays < 1 || resolvedExpiresInDays > 90)
            throw new DomainBadRequestException("validation.token.agent.expiresAt.max");
        DeviceCodeData data = new DeviceCodeData(deviceCode, userCode, DeviceCodeStatus.PENDING, null,
                resolvedClientId, resolvedClientName, requestedScopes, requestedNamespaceSlug, resolvedExpiresInDays);

        redisTemplate.opsForValue().set(
            DEVICE_CODE_PREFIX + deviceCode, data, PENDING_CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(
            USER_CODE_PREFIX + userCode, deviceCode, PENDING_CODE_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(
            DEVICE_KNOWN_PREFIX + deviceCode, "known", PENDING_CODE_TTL_MINUTES + 1, TimeUnit.MINUTES);

        return new DeviceCodeResponse(deviceCode, userCode, verificationUri, EXPIRES_IN_SECONDS, POLL_INTERVAL_SECONDS);
    }

    public DeviceCodeResponse generateDeviceCode(String clientId, String clientName, List<String> scopes,
                                                 String requestedNamespaceSlug) {
        return generateDeviceCode(clientId, clientName, scopes, requestedNamespaceSlug, null);
    }

    public DeviceCodeResponse generateDeviceCode() {
        return generateDeviceCode(null, null, List.of("skill:read", "skill:publish"), null, null);
    }

    /**
     * Marks a user code as authorized by a concrete authenticated user.
     */
    public void authorizeDeviceCode(String userCode, String userId, Long namespaceId) {
        String deviceCode = (String) redisTemplate.opsForValue().get(USER_CODE_PREFIX + userCode);
        if (deviceCode == null) {
            throw new DomainBadRequestException("error.deviceAuth.userCode.invalid");
        }

        DeviceCodeData data = (DeviceCodeData) redisTemplate.opsForValue().get(DEVICE_CODE_PREFIX + deviceCode);
        if (data == null) {
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.expired");
        }
        if (isExpired(data)) {
            expire(deviceCode, data);
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.expired");
        }

        switch (data.getStatus()) {
            case PENDING -> {
                data.setStatus(DeviceCodeStatus.AUTHORIZED);
                data.setUserId(userId);
                data.setNamespaceId(namespaceId);
                savePending(deviceCode, data);
            }
            case AUTHORIZED -> {
                if (!userId.equals(data.getUserId())) {
                    throw new DomainBadRequestException("error.deviceAuth.deviceCode.alreadyAuthorized");
                }
            }
            case DENIED -> throw new DomainBadRequestException("error.deviceAuth.deviceCode.denied");
            case USED -> throw new DomainBadRequestException("error.deviceAuth.deviceCode.used");
        }
    }

    public void authorizeDeviceCode(String userCode, String userId) { authorizeDeviceCode(userCode, userId, null); }

    public void denyDeviceCode(String userCode) {
        String deviceCode = (String) redisTemplate.opsForValue().get(USER_CODE_PREFIX + userCode);
        DeviceCodeData data = deviceCode == null ? null : (DeviceCodeData) redisTemplate.opsForValue().get(DEVICE_CODE_PREFIX + deviceCode);
        if (data == null) throw new DomainBadRequestException("error.deviceAuth.userCode.invalid");
        if (isExpired(data)) {
            expire(deviceCode, data);
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.expired");
        }
        data.setStatus(DeviceCodeStatus.DENIED);
        savePending(deviceCode, data);
    }

    public DeviceCodeData inspectUserCode(String userCode) {
        String deviceCode = (String) redisTemplate.opsForValue().get(USER_CODE_PREFIX + userCode);
        DeviceCodeData data = deviceCode == null ? null : (DeviceCodeData) redisTemplate.opsForValue().get(DEVICE_CODE_PREFIX + deviceCode);
        if (data == null) throw new DomainBadRequestException("error.deviceAuth.userCode.invalid");
        if (isExpired(data)) {
            expire(deviceCode, data);
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.expired");
        }
        return data;
    }

    /**
     * Polls the device code and either returns a pending response or redeems it
     * into an API token exactly once.
     */
    public DeviceTokenResponse pollToken(String deviceCode) {
        DeviceCodeData data = (DeviceCodeData) redisTemplate.opsForValue().get(DEVICE_CODE_PREFIX + deviceCode);

        if (data == null) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(DEVICE_KNOWN_PREFIX + deviceCode))) {
                return DeviceTokenResponse.error("expired_token");
            }
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.invalid");
        }
        if (isExpired(data)) {
            expire(deviceCode, data);
            return DeviceTokenResponse.error("expired_token");
        }

        long now = System.currentTimeMillis();
        if (data.getLastPolledAt() > 0 && now - data.getLastPolledAt() < data.getPollIntervalSeconds() * 1000L) {
            data.setPollIntervalSeconds(Math.min(30, data.getPollIntervalSeconds() + 5));
            data.setLastPolledAt(now);
            savePending(deviceCode, data);
            return DeviceTokenResponse.error("slow_down");
        }
        data.setLastPolledAt(now);
        savePending(deviceCode, data);
        return switch (data.getStatus()) {
            case PENDING -> DeviceTokenResponse.pending();
            case AUTHORIZED -> redeemAuthorizedDeviceCode(deviceCode, data);
            case DENIED -> DeviceTokenResponse.error("access_denied");
            case USED -> throw new DomainBadRequestException("error.deviceAuth.deviceCode.used");
        };
    }

    private DeviceTokenResponse redeemAuthorizedDeviceCode(String deviceCode, DeviceCodeData data) {
        boolean claimed = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
            DEVICE_CLAIM_PREFIX + deviceCode,
            "claimed",
            USED_CODE_TTL_MINUTES,
            TimeUnit.MINUTES
        ));
        if (!claimed) {
            throw new DomainBadRequestException("error.deviceAuth.deviceCode.used");
        }

        try {
            if (!StringUtils.hasText(data.getUserId())) {
                throw new DomainBadRequestException("error.deviceAuth.deviceCode.invalid");
            }

            if (data.getNamespaceId() == null)
                throw new DomainBadRequestException("validation.token.namespace.required");
            String shortId = data.getClientId().substring(0, Math.min(8, data.getClientId().length()));
            String scopeJson = data.getScopes().stream().map(scope -> "\"" + scope + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            String token = apiTokenService.rotateAgentToken(
                data.getUserId(),
                "Agent " + data.getClientName() + " " + shortId,
                scopeJson,
                Instant.now().plus(data.getExpiresInDays(), ChronoUnit.DAYS).toString(),
                data.getClientId(), data.getClientName(), data.getNamespaceId()
            ).rawToken();

            data.setStatus(DeviceCodeStatus.USED);
            redisTemplate.opsForValue().set(
                DEVICE_CODE_PREFIX + deviceCode,
                data,
                USED_CODE_TTL_MINUTES,
                TimeUnit.MINUTES
            );
            redisTemplate.delete(USER_CODE_PREFIX + data.getUserCode());
            return DeviceTokenResponse.success(token);
        } catch (RuntimeException ex) {
            redisTemplate.delete(DEVICE_CLAIM_PREFIX + deviceCode);
            throw ex;
        }
    }

    private String generateRandomDeviceCode() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateUserCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) code.append('-');
            code.append(USER_CODE_CHARS.charAt(random.nextInt(USER_CODE_CHARS.length())));
        }
        return code.toString();
    }

    private boolean isExpired(DeviceCodeData data) {
        return data.getExpiresAtEpochMillis() > 0 && System.currentTimeMillis() >= data.getExpiresAtEpochMillis();
    }

    private void savePending(String deviceCode, DeviceCodeData data) {
        long remainingMillis = Math.max(1L, data.getExpiresAtEpochMillis() - System.currentTimeMillis());
        redisTemplate.opsForValue().set(
                DEVICE_CODE_PREFIX + deviceCode, data, remainingMillis, TimeUnit.MILLISECONDS);
    }

    private void expire(String deviceCode, DeviceCodeData data) {
        redisTemplate.delete(DEVICE_CODE_PREFIX + deviceCode);
        redisTemplate.delete(USER_CODE_PREFIX + data.getUserCode());
    }
}
