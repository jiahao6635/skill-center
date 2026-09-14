package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iflytek.skillhub.domain.invocation.SkillInvocation;
import com.iflytek.skillhub.domain.invocation.SkillInvocationRepository;
import com.iflytek.skillhub.dto.SkillInvocationBatchResponse;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Per-item validation and isolated durable ingestion; storage failures fail the request for replay. */
@Service
public class SkillInvocationAppService {
    private static final Set<String> OPTIONAL = Set.of("name", "uid", "prompt_id", "tool_call_id", "agent_id",
            "skill_plugin", "skill_coordinate", "skill_version", "product", "product_version");
    private static final Set<String> REQUIRED = Set.of("source", "event_id", "occurred_at", "observed_at",
            "time_source", "email", "session_id", "skill_name", "trigger_mode", "evidence", "client_product");
    private final SkillInvocationRepository repository;
    public SkillInvocationAppService(SkillInvocationRepository repository) { this.repository = repository; }

    public SkillInvocationBatchResponse ingest(List<JsonNode> inputs) {
        var results = new ArrayList<SkillInvocationBatchResponse.InvocationResult>();
        for (JsonNode input : inputs) {
            SkillInvocation event;
            try { event = validate(input); }
            catch (IllegalArgumentException error) {
                results.add(new SkillInvocationBatchResponse.InvocationResult(input == null ? "" : input.path("event_id").asText(""),
                        "rejected", "INVALID_EVENT"));
                continue;
            }
            var result = repository.save(event);
            results.add(new SkillInvocationBatchResponse.InvocationResult(event.eventId(),
                    result == SkillInvocationRepository.Outcome.ACCEPTED ? "accepted"
                    : result == SkillInvocationRepository.Outcome.DUPLICATE ? "duplicate" : "rejected",
                    result == SkillInvocationRepository.Outcome.CONFLICT ? "EVENT_ID_CONFLICT" : null));
        }
        return new SkillInvocationBatchResponse(results);
    }

    static SkillInvocation validate(JsonNode input) {
        if (input == null || !input.isObject()) throw new IllegalArgumentException();
        Map<String, String> data = new LinkedHashMap<>();
        for (String field : REQUIRED) {
            JsonNode value = input.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException();
            data.put(field, value.asText().trim());
        }
        for (String field : OPTIONAL) {
            JsonNode value = input.get(field);
            if (value == null || value.isNull()) continue;
            if (!value.isTextual()) throw new IllegalArgumentException();
            if (!value.asText().isBlank()) data.put(field, value.asText().trim());
        }
        for (var entry : data.entrySet()) {
            if (entry.getValue().length() > (entry.getKey().equals("skill_name") ? 512 : 256)
                    || entry.getValue().chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException();
        }
        String email = data.get("email").toLowerCase(Locale.ROOT);
        if (!email.matches("[^@\\s]+@[^@\\s]+") || email.length() > 256) throw new IllegalArgumentException();
        data.put("email", email);
        if (!"qoder-request-logger".equals(data.get("source"))
                || !data.get("event_id").matches("[a-f0-9]{64}")
                || !Set.of("source", "observed").contains(data.get("time_source"))
                || !Set.of("automatic", "manual").contains(data.get("trigger_mode"))
                || !Set.of("hook", "transcript_tool_use", "transcript_slash_command").contains(data.get("evidence"))
                || !Set.of("qoder", "qoder_ide", "qoderwork", "unknown").contains(data.get("client_product"))) throw new IllegalArgumentException();
        String coordinate = data.get("skill_coordinate");
        if (coordinate != null && !coordinate.matches("@[a-zA-Z0-9._-]{1,64}/[a-zA-Z0-9._-]{1,128}")) throw new IllegalArgumentException();
        try {
            Instant occurred = Instant.parse(data.get("occurred_at")), observed = Instant.parse(data.get("observed_at"));
            // PostgreSQL timestamp range and reasonable contemporary event dates.
            if (occurred.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                    || observed.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                    || occurred.isAfter(Instant.now().plusSeconds(86400))
                    || observed.isAfter(Instant.now().plusSeconds(86400))) throw new IllegalArgumentException();
            return new SkillInvocation(data.get("source"), data.get("event_id"), email, data.get("session_id"),
                    data.get("skill_name"), data.get("client_product"), occurred, observed,
                    data.get("time_source"), data.get("evidence"), data);
        } catch (java.time.DateTimeException e) { throw new IllegalArgumentException(e); }
    }
}
