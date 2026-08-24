package com.iflytek.skillhub.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.config.SkillUsageProperties;
import com.iflytek.skillhub.domain.event.SkillDownloadedEvent;
import com.iflytek.skillhub.domain.usage.SkillUsageAction;
import com.iflytek.skillhub.domain.usage.SkillUsageRecorder;
import com.iflytek.skillhub.domain.usage.SkillUsageRequestContext;
import com.iflytek.skillhub.domain.usage.UsageCommand;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.usage.UsageDedupService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the usage ledger from domain events.
 *
 * <p>DOWNLOAD uses a plain synchronous {@code @EventListener}: the download
 * counters are incremented in already-committed short transactions with no
 * surrounding transaction, so an AFTER_COMMIT transactional listener would be
 * silently dropped. The ledger insert runs in its own REQUIRES_NEW transaction;
 * a failure is a metric, never a failed download.
 */
@Component
public class SkillUsageRecordingListener {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageRecordingListener.class);

    private final SkillUsageRecorder recorder;
    private final UsageDedupService dedupService;
    private final SkillUsageProperties properties;
    private final SkillHubMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public SkillUsageRecordingListener(SkillUsageRecorder recorder,
                                       UsageDedupService dedupService,
                                       SkillUsageProperties properties,
                                       SkillHubMetrics metrics,
                                       ObjectMapper objectMapper,
                                       Clock clock,
                                       PlatformTransactionManager transactionManager) {
        this.recorder = recorder;
        this.dedupService = dedupService;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onDownloaded(SkillDownloadedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        // Two-arg overload / internal pulls / tests: no attribution, skip.
        if (event.requestContext() == null || event.actorKey() == null || event.actorKey().isBlank()) {
            return;
        }
        String subject = event.skillId() + ":" + event.versionId();
        if (!dedupService.tryAcquire(SkillUsageAction.DOWNLOAD, subject, event.actorKey())) {
            metrics.incrementUsageDedupHit(SkillUsageAction.DOWNLOAD.name());
            return;
        }
        Instant occurredAt = Instant.now(clock);
        UsageCommand command = new UsageCommand(
                occurredAt,
                SkillUsageAction.DOWNLOAD,
                event.skillId(),
                event.versionId(),
                null,
                event.actorUserId(),
                event.actorKey(),
                event.actorKind(),
                event.requestContext(),
                dedupService.dedupKey(SkillUsageAction.DOWNLOAD, subject, event.actorKey(), occurredAt),
                downloadPayload(event));
        record(command, event.requestContext());
    }

    private void record(UsageCommand command, SkillUsageRequestContext context) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> recorder.record(command));
            metrics.incrementUsageRecorded(command.action().name(),
                    context != null && context.client() != null ? context.client().name() : "UNKNOWN");
        } catch (Exception ex) {
            metrics.incrementUsageRecordFailure(command.action().name());
            log.warn("Failed to record usage event [action={}, skillId={}]",
                    command.action(), command.skillId(), ex);
        }
    }

    private String downloadPayload(SkillDownloadedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "version", event.version());
        putIfPresent(payload, "delivery", event.delivery());
        putIfPresent(payload, "namespaceSlug", event.namespaceSlug());
        putIfPresent(payload, "skillSlug", event.skillSlug());
        return toJson(payload);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize usage payload, falling back to empty object", ex);
            return "{}";
        }
    }
}
