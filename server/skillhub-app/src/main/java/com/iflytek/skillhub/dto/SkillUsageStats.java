package com.iflytek.skillhub.dto;

import java.time.Instant;

/** Read models for the administrator-only invocation dashboard, separate from downloads. */
public final class SkillUsageStats {
    private SkillUsageStats() { }

    public record Summary(long invocationCount, long userCount, long skillCount, long sessionCount,
                          Instant queriedAt) { }
    public record SkillRank(String skillName, long invocationCount, long userCount, Instant lastUsedAt,
                            boolean unlinked, long rank, long peakCount, Long downloadCount, Integer starCount) { }
    public record UserRank(String email, String name, long invocationCount, long skillCount,
                           Instant lastUsedAt, long rank) { }
    public record UserOption(String email, String name) { }
}
