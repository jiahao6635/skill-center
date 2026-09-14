package com.iflytek.skillhub.dto;

import java.time.Instant;

public record SkillInvocationItem(long id, String centerUserId, Long centerSkillId,
        Instant receivedAt, SkillInvocationInput event) {}
