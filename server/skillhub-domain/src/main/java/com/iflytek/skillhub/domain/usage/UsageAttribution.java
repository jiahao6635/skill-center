package com.iflytek.skillhub.domain.usage;

/**
 * Who is performing the current request, assembled at the HTTP edge and passed
 * as a method argument down to the domain download methods. ThreadLocal is
 * forbidden; a null or non-recordable attribution means the usage listener
 * skips the write while public counters still increment.
 */
public record UsageAttribution(
        String actorUserId,
        String actorKey,
        SkillUsageActorKind actorKind,
        SkillUsageRequestContext requestContext
) {
    public boolean isRecordable() {
        return actorKey != null && !actorKey.isBlank() && requestContext != null;
    }
}
