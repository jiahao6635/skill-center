package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** External telemetry contract. Deliberately excludes conversation contents. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SkillInvocationInput(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String source, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String eventId, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String occurredAt, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String observedAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String timeSource, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String email, String name, String uid, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String sessionId, String promptId,
        String toolCallId, String agentId, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String skillName, String skillPlugin, String skillCoordinate,
        String skillVersion, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String triggerMode, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String evidence, @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String clientProduct,
        String product, String productVersion) {}
