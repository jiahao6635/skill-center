package com.iflytek.skillhub.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for the skill usage ledger (dedup windows, retention, rollup lock,
 * async executor sizing). See docs/15-skill-usage-analytics.md §5.12.
 */
@Component
@ConfigurationProperties(prefix = "skillhub.usage")
public class SkillUsageProperties {

    private boolean enabled = true;
    private Duration viewDedupTtl = Duration.ofMinutes(30);
    private Duration downloadDedupTtl = Duration.ofMinutes(15);
    private Duration searchDedupTtl = Duration.ofMinutes(5);
    private Duration mutationDedupTtl = Duration.ofHours(24);
    private int piiRetentionDays = 30;
    private boolean eventRetentionEnabled = false;
    private int eventRetentionDays = 180;
    private Duration rollupLockTtl = Duration.ofMinutes(5);
    private final Executor executor = new Executor();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getViewDedupTtl() { return viewDedupTtl; }
    public void setViewDedupTtl(Duration viewDedupTtl) { this.viewDedupTtl = viewDedupTtl; }

    public Duration getDownloadDedupTtl() { return downloadDedupTtl; }
    public void setDownloadDedupTtl(Duration downloadDedupTtl) { this.downloadDedupTtl = downloadDedupTtl; }

    public Duration getSearchDedupTtl() { return searchDedupTtl; }
    public void setSearchDedupTtl(Duration searchDedupTtl) { this.searchDedupTtl = searchDedupTtl; }

    public Duration getMutationDedupTtl() { return mutationDedupTtl; }
    public void setMutationDedupTtl(Duration mutationDedupTtl) { this.mutationDedupTtl = mutationDedupTtl; }

    public int getPiiRetentionDays() { return piiRetentionDays; }
    public void setPiiRetentionDays(int piiRetentionDays) { this.piiRetentionDays = piiRetentionDays; }

    public boolean isEventRetentionEnabled() { return eventRetentionEnabled; }
    public void setEventRetentionEnabled(boolean eventRetentionEnabled) { this.eventRetentionEnabled = eventRetentionEnabled; }

    public int getEventRetentionDays() { return eventRetentionDays; }
    public void setEventRetentionDays(int eventRetentionDays) { this.eventRetentionDays = eventRetentionDays; }

    public Duration getRollupLockTtl() { return rollupLockTtl; }
    public void setRollupLockTtl(Duration rollupLockTtl) { this.rollupLockTtl = rollupLockTtl; }

    public Executor getExecutor() { return executor; }

    public static class Executor {
        private int coreSize = 2;
        private int maxSize = 8;
        private int queueCapacity = 1000;

        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int coreSize) { this.coreSize = coreSize; }

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }
}
