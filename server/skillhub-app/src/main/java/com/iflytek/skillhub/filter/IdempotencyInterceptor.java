package com.iflytek.skillhub.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.idempotency.IdempotencyRecord;
import com.iflytek.skillhub.domain.idempotency.IdempotencyRecordRepository;
import com.iflytek.skillhub.domain.idempotency.IdempotencyStatus;
import com.iflytek.skillhub.dto.ApiResponse;
import com.iflytek.skillhub.security.RequestAuthorizationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Prevents duplicate execution of mutating HTTP requests identified by
 * {@code X-Request-Id}.
 *
 * <p>Redis is treated as the fast-path cache, while PostgreSQL remains the
 * durable source of truth when cache access fails.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String REDIS_KEY_PREFIX = "idempotency:";
    private static final long EXPIRY_HOURS = 24;
    private static final String OWNER_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".owner";

    private final StringRedisTemplate redisTemplate;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyInterceptor(StringRedisTemplate redisTemplate,
                                  IdempotencyRecordRepository idempotencyRecordRepository,
                                  ObjectMapper objectMapper,
                                  Clock clock) {
        this.redisTemplate = redisTemplate;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Rejects duplicate mutating requests before controller execution and
     * creates a processing marker for first-seen request identifiers.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if (!method.equals("POST") && !method.equals("PUT") && !method.equals("DELETE")) {
            return true;
        }

        String requestId = idempotencyKey(request);
        if (requestId == null || requestId.isEmpty()) {
            return true;
        }
        RequestBinding binding = binding(request);

        // Check Redis first
        String redisKey = REDIS_KEY_PREFIX + requestId;
        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(redisKey);
        } catch (Exception ignored) {
            // Redis unavailable, fall through to PostgreSQL
        }

        // Check PostgreSQL fallback
        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (!matches(record, binding)) {
                writeConflictResponse(response, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency key was already used for a different request");
                return false;
            }
            if (record.getStatus() == IdempotencyStatus.PROCESSING) {
                writeConflictResponse(response, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        "An identical request is still processing");
                return false;
            }
            replay(response, record);
            return false;
        }
        if (cached != null) {
            writeConflictResponse(response, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "Idempotency state is temporarily unavailable; retry later");
            return false;
        }

        // Create new record
        Instant now = Instant.now(clock);
        IdempotencyRecord newRecord = new IdempotencyRecord(
            requestId, (String) null, (Long) null, IdempotencyStatus.PROCESSING,
            (Integer) null, now, now.plusSeconds(EXPIRY_HOURS * 3600));
        applyBinding(newRecord, binding);
        try {
            idempotencyRecordRepository.save(newRecord);
        } catch (DataIntegrityViolationException race) {
            writeConflictResponse(response, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                    "An identical request is still processing");
            return false;
        }
        request.setAttribute(OWNER_ATTRIBUTE, Boolean.TRUE);

        // Cache in Redis
        try {
            redisTemplate.opsForValue().set(redisKey, "PROCESSING", EXPIRY_HOURS, TimeUnit.HOURS);
        } catch (Exception ignored) {
            // Redis unavailable, PostgreSQL is the source of truth
        }

        return true;
    }

    /**
     * Finalizes the idempotency record with the observed response status once
     * request processing has completed.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        if (!method.equals("POST") && !method.equals("PUT") && !method.equals("DELETE")) {
            return;
        }

        String requestId = idempotencyKey(request);
        if (requestId == null || requestId.isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(request.getAttribute(OWNER_ATTRIBUTE))) return;

        Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            record.setStatus(ex == null ? IdempotencyStatus.COMPLETED : IdempotencyStatus.FAILED);
            record.setResponseStatusCode(response.getStatus());
            record.setResponseBody(responseBody(response));
            idempotencyRecordRepository.save(record);

            try {
                String redisKey = REDIS_KEY_PREFIX + requestId;
                redisTemplate.opsForValue().set(redisKey, record.getStatus().name(), EXPIRY_HOURS, TimeUnit.HOURS);
            } catch (Exception ignored) {
                // Redis unavailable
            }
        }
    }

    private void writeConflictResponse(HttpServletResponse response, String code, String message) throws Exception {
        ApiResponse<java.util.Map<String, String>> body = new ApiResponse<>(409, message,
                java.util.Map.of("code", code),
                Instant.now(clock), null);
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void replay(HttpServletResponse response, IdempotencyRecord record) throws Exception {
        response.setStatus(record.getResponseStatusCode() != null
                ? record.getResponseStatusCode() : HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        if (record.getResponseBody() != null) {
            response.getWriter().write(record.getResponseBody());
        } else {
            writeConflictResponse(response, "IDEMPOTENCY_RESPONSE_UNAVAILABLE",
                    "The original response is no longer available");
        }
    }

    private String idempotencyKey(HttpServletRequest request) {
        String value = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        return value != null && !value.isBlank() ? value : request.getHeader(REQUEST_ID_HEADER);
    }

    private RequestBinding binding(HttpServletRequest request) {
        RequestAuthorizationContext context = request.getAttribute("authorizationContext") instanceof RequestAuthorizationContext value
                ? value : null;
        String actor = context != null ? context.userId() : (String) request.getAttribute("userId");
        Long tokenId = context != null && context.tokenGrant() != null ? context.tokenGrant().tokenId() : null;
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(";")) contentType = contentType.substring(0, contentType.indexOf(';'));
        String suppliedDigest = request.getHeader("Idempotency-Request-Digest");
        String material = request.getMethod() + "\n" + request.getRequestURI() + "\n"
                + Objects.toString(request.getQueryString(), "") + "\n"
                + Objects.toString(contentType, "") + "\n" + request.getContentLengthLong();
        return new RequestBinding(actor, tokenId, request.getMethod(), request.getRequestURI(),
                suppliedDigest != null && suppliedDigest.matches("[a-fA-F0-9]{64}")
                        ? suppliedDigest.toLowerCase() : sha256(material));
    }

    private boolean matches(IdempotencyRecord record, RequestBinding binding) {
        return compatible(record.getActorUserId(), binding.actorUserId())
                && compatible(record.getTokenId(), binding.tokenId())
                && compatible(record.getHttpMethod(), binding.method())
                && compatible(record.getRequestPath(), binding.path())
                && compatible(record.getRequestDigest(), binding.digest());
    }

    private boolean compatible(Object stored, Object actual) {
        return stored == null || Objects.equals(stored, actual);
    }

    private void applyBinding(IdempotencyRecord record, RequestBinding binding) {
        record.setActorUserId(binding.actorUserId());
        record.setTokenId(binding.tokenId());
        record.setHttpMethod(binding.method());
        record.setRequestPath(binding.path());
        record.setRequestDigest(binding.digest());
    }

    private String responseBody(HttpServletResponse response) {
        if (response instanceof ContentCachingResponseWrapper wrapper) {
            return new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record RequestBinding(String actorUserId, Long tokenId, String method, String path, String digest) {}
}
