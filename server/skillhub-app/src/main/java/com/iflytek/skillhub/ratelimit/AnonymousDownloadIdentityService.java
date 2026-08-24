package com.iflytek.skillhub.ratelimit;

import com.iflytek.skillhub.config.DownloadRateLimitProperties;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Assigns stable anonymous identities for download rate limiting by combining client IP data with
 * a signed cookie.
 */
@Component
public class AnonymousDownloadIdentityService {

    private static final String COOKIE_VERSION = "v1";
    private static final int MIN_SECRET_LENGTH = 32;
    private static final Set<String> DISALLOWED_SECRET_VALUES = Set.of(
            "change-me-in-production",
            "replace-me",
            "replace-with-random-download-secret-32-bytes"
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DownloadRateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;

    public AnonymousDownloadIdentityService(DownloadRateLimitProperties properties,
                                            ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @PostConstruct
    void validateAnonymousCookieSecret() {
        String secret = properties.getAnonymousCookieSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET is required");
        }
        String trimmedSecret = secret.trim();
        if (DISALLOWED_SECRET_VALUES.contains(trimmedSecret)) {
            throw new IllegalStateException("SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET must not use the default placeholder");
        }
        if (trimmedSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET must be at least 32 characters");
        }
    }

    public AnonymousDownloadIdentity resolve(HttpServletRequest request, HttpServletResponse response) {
        String ip = clientIpResolver.resolve(request);
        String cookieId = extractValidCookieId(request);
        if (cookieId == null) {
            cookieId = generateId();
            response.addHeader("Set-Cookie", buildCookie(cookieId, request).toString());
        }
        return new AnonymousDownloadIdentity(hash(ip), hash(cookieId));
    }

    /**
     * Verify skillhub_anon_dl without ever minting: never writes Set-Cookie.
     * Empty when the cookie is missing or invalid. Returned hash is the SHA-256
     * hex of the cookie id (64 chars), identical to {@code resolve().cookieHash}.
     * Usage attribution for SEARCH / VIEW must use this instead of resolve().
     */
    public java.util.Optional<String> peekCookieHash(HttpServletRequest request) {
        String cookieId = extractValidCookieId(request);
        return cookieId == null ? java.util.Optional.empty() : java.util.Optional.of(hash(cookieId));
    }

    /**
     * Cookieless anonymous fingerprint (CLI / compat): 32 hex chars from the
     * first 16 bytes of HMAC-SHA256(downloadCookieSecret, ip + "\n" + ua).
     * Distinct in shape from the 64-hex cookie hash by design.
     */
    public String fallbackIdentityHash(String clientIp, String userAgent) {
        String normalizedUserAgent = userAgent == null ? "" : userAgent.trim();
        if (normalizedUserAgent.length() > 512) {
            normalizedUserAgent = normalizedUserAgent.substring(0, 512);
        }
        String ip = clientIp == null ? "" : clientIp;
        byte[] mac = sign(ip + "\n" + normalizedUserAgent);
        StringBuilder builder = new StringBuilder(32);
        for (int i = 0; i < 16; i++) {
            builder.append(String.format("%02x", mac[i]));
        }
        return builder.toString();
    }

    private String extractValidCookieId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.getAnonymousCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .map(this::parseAndVerify)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String parseAndVerify(String cookieValue) {
        if (cookieValue == null) {
            return null;
        }
        String[] parts = cookieValue.split("\\.", 3);
        if (parts.length != 3 || !COOKIE_VERSION.equals(parts[0])) {
            return null;
        }
        byte[] expected = sign(parts[1]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return MessageDigest.isEqual(expected, actual) ? parts[1] : null;
    }

    private ResponseCookie buildCookie(String cookieId, HttpServletRequest request) {
        Duration maxAge = properties.getAnonymousCookieMaxAge();
        return ResponseCookie.from(properties.getAnonymousCookieName(), encodeCookieValue(cookieId))
                .httpOnly(true)
                .secure(isSecure(request))
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && forwardedProto.equalsIgnoreCase("https");
    }

    private String encodeCookieValue(String id) {
        return COOKIE_VERSION + "." + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(id));
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAnonymousCookieSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign anonymous download cookie", ex);
        }
    }

    private String generateId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to hash anonymous download identity", ex);
        }
    }

    public record AnonymousDownloadIdentity(String ipHash, String cookieHash) {
    }
}
