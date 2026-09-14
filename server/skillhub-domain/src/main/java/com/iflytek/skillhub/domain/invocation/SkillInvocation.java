package com.iflytek.skillhub.domain.invocation;

import java.time.Instant;
import java.util.Map;

/** Validated metadata only; no prompts, tool arguments or outputs. */
public record SkillInvocation(String source, String eventId, String email, String sessionId,
        String skillName, String product, Instant occurredAt, Instant observedAt,
        String timeSource, String evidence, Map<String, String> metadata) {
    public SkillInvocation { metadata = Map.copyOf(metadata); }
}
