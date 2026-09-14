package com.iflytek.skillhub.infra.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.invocation.SkillInvocation;
import com.iflytek.skillhub.domain.invocation.SkillInvocationRepository;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.time.ZoneOffset;
import java.util.*;

/** Database uniqueness is the authority, including after Redis/process restarts. */
@Repository
public class JdbcSkillInvocationRepository implements SkillInvocationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcSkillInvocationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome save(SkillInvocation event) {
        String coordinate = event.metadata().get("skill_coordinate");
        String namespace = null, slug = null;
        if (coordinate != null) { String[] parts = coordinate.substring(1).split("/", 2); namespace = parts[0]; slug = parts[1]; }
        String json;
        try { json = mapper.writeValueAsString(event.metadata()); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        var p = new MapSqlParameterSource().addValue("source", event.source()).addValue("eventId", event.eventId())
                .addValue("email", event.email()).addValue("session", event.sessionId()).addValue("skillName", event.skillName())
                .addValue("product", event.product()).addValue("occurred", event.occurredAt().atOffset(ZoneOffset.UTC))
                .addValue("observed", event.observedAt().atOffset(ZoneOffset.UTC)).addValue("timeSource", event.timeSource())
                .addValue("evidence", event.evidence()).addValue("json", json).addValue("namespace", namespace).addValue("slug", slug);
        // Do not guess when multiple accounts share an email. Preserve the reported identity instead.
        String user = jdbc.query("SELECT id FROM user_account WHERE lower(trim(email)) = :email LIMIT 2", p,
                (rs, n) -> rs.getString(1)).stream().reduce((a, b) -> "").filter(s -> !s.isEmpty()).orElse(null);
        List<Long> skills = coordinate == null ? List.of() : jdbc.query("SELECT s.id FROM skill s JOIN namespace n ON n.id=s.namespace_id WHERE n.slug=:namespace AND s.slug=:slug", p, (rs, n) -> rs.getLong(1));
        p.addValue("userId", user).addValue("skillId", skills.size() == 1 ? skills.getFirst() : null, java.sql.Types.BIGINT);
        List<Long> inserted = jdbc.query("""
            INSERT INTO skill_invocation_event(source,event_id,email,session_id,skill_name,client_product,
                occurred_at,observed_at,time_source,evidence,metadata_json,center_user_id,center_skill_id)
            VALUES (:source,:eventId,:email,:session,:skillName,:product,:occurred,:observed,:timeSource,:evidence,
                CAST(:json AS jsonb),:userId,:skillId)
            ON CONFLICT(source,event_id) DO NOTHING RETURNING id
            """, p, (rs, n) -> rs.getLong(1));
        if (!inserted.isEmpty()) return Outcome.ACCEPTED;
        List<Boolean> matches = jdbc.query("SELECT email=:email AND session_id=:session AND skill_name=:skillName FROM skill_invocation_event WHERE source=:source AND event_id=:eventId FOR UPDATE", p, (rs, n) -> rs.getBoolean(1));
        if (matches.isEmpty() || !matches.getFirst()) return Outcome.CONFLICT;
        // Source timestamps outrank observation time; transcript outranks hooks at equal quality.
        jdbc.update("""
            UPDATE skill_invocation_event SET occurred_at=:occurred,time_source=:timeSource,evidence=:evidence,
              client_product=CASE WHEN :product='unknown' THEN client_product ELSE :product END,
              metadata_json=metadata_json || CAST(:json AS jsonb),
              center_user_id=COALESCE(center_user_id,:userId),center_skill_id=COALESCE(center_skill_id,:skillId)
            WHERE source=:source AND event_id=:eventId AND
              ((time_source='observed' AND :timeSource='source') OR
               (time_source=:timeSource AND ((evidence='hook' AND :evidence <> 'hook') OR
                 (evidence='transcript_tool_use' AND :evidence='transcript_slash_command'))))
            """, p);
        return Outcome.DUPLICATE;
    }
}
