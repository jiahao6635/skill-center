package com.iflytek.skillhub.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SkillInvocationBatchResponse(List<InvocationResult> results) {
    public record InvocationResult(@JsonProperty("event_id") String eventId, String status, String error) {}
}
