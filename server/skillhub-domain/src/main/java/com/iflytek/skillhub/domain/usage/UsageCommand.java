package com.iflytek.skillhub.domain.usage;

import java.time.Instant;

/**
 * Frozen write command for the usage ledger. All later PRs must go through
 * {@link SkillUsageRecorder#record(UsageCommand)}; a second insert path is
 * forbidden. {@code requestContext} may be null (no-HTTP publish paths);
 * {@code dedupKey} may be null when no dedup window applies.
 */
public record UsageCommand(
        Instant occurredAt,
        SkillUsageAction action,
        Long skillId,
        Long skillVersionId,
        Long namespaceId,
        String actorUserId,
        String actorKey,
        SkillUsageActorKind actorKind,
        SkillUsageRequestContext requestContext,
        String dedupKey,
        String payloadJson
) {}
