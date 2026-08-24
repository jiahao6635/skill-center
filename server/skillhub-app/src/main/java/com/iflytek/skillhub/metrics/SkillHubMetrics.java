package com.iflytek.skillhub.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Small facade over Micrometer that centralizes metric names and tags used by backend flows.
 */
@Component
public class SkillHubMetrics {

    private final MeterRegistry meterRegistry;

    public SkillHubMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementUserRegister() {
        meterRegistry.counter("skillhub.user.register").increment();
    }

    public void recordLocalLogin(boolean success) {
        meterRegistry.counter(
            "skillhub.auth.login",
            "method", "local",
            "result", success ? "success" : "failure"
        ).increment();
    }

    public void incrementSkillPublish(String namespace, String status) {
        meterRegistry.counter(
            "skillhub.skill.publish",
            "namespace", namespace,
            "status", status
        ).increment();
    }

    public void recordDownloadDelivery(String mode, boolean fallbackBundle) {
        meterRegistry.counter(
            "skillhub.skill.download.delivery",
            "mode", mode,
            "fallback_bundle", Boolean.toString(fallbackBundle)
        ).increment();
    }

    public void incrementBundleMissingFallback() {
        meterRegistry.counter("skillhub.skill.download.bundle_missing_fallback").increment();
    }

    public void incrementRateLimitExceeded(String category) {
        meterRegistry.counter(
            "skillhub.ratelimit.exceeded",
            "category", category
        ).increment();
    }

    public void incrementStorageAccessFailure(String operation) {
        meterRegistry.counter(
            "skillhub.storage.failure",
            "operation", operation
        ).increment();
    }

    public void incrementUsageRecorded(String action, String client) {
        meterRegistry.counter(
            "skillhub.usage.recorded",
            "action", action,
            "client", client
        ).increment();
    }

    public void incrementUsageDedupHit(String action) {
        meterRegistry.counter(
            "skillhub.usage.dedup_hit",
            "action", action
        ).increment();
    }

    public void incrementUsageRecordFailure(String action) {
        meterRegistry.counter(
            "skillhub.usage.record.failure",
            "action", action
        ).increment();
    }

    public void incrementUsageExecutorRejected() {
        meterRegistry.counter("skillhub.usage.executor.rejected").increment();
    }

    public void incrementUsageRetentionPiiCleared(long rows) {
        meterRegistry.counter("skillhub.usage.retention.pii_cleared").increment(rows);
    }

    public void incrementUsageRetentionDeleted(long rows) {
        meterRegistry.counter("skillhub.usage.retention.deleted").increment(rows);
    }
}
