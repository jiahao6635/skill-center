package com.iflytek.skillhub.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.dto.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Locale;

@Repository
public class SkillInvocationQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SkillInvocationQueryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    @Transactional(readOnly=true)
    public PageResponse<SkillInvocationItem> list(int page, int size, String email, String userId,
            String skillName, Long skillId, String product, String session, Instant from, Instant to) {
        var p = new MapSqlParameterSource().addValue("limit",size).addValue("offset", (long)page*size);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        add(where,p,"email",email == null ? null : email.trim().toLowerCase(Locale.ROOT));
        add(where,p,"center_user_id",userId); add(where,p,"skill_name",skillName);
        add(where,p,"center_skill_id",skillId); add(where,p,"client_product",product); add(where,p,"session_id",session);
        if (from != null) { where.append(" AND occurred_at >= :from"); p.addValue("from",from.atOffset(ZoneOffset.UTC)); }
        if (to != null) { where.append(" AND occurred_at < :to"); p.addValue("to",to.atOffset(ZoneOffset.UTC)); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM skill_invocation_event"+where,p,Long.class);
        var items = jdbc.query("SELECT * FROM skill_invocation_event"+where+" ORDER BY occurred_at DESC,id DESC LIMIT :limit OFFSET :offset",p,(rs,n)-> {
            try {
                // Columns are authoritative even after an evidence upgrade.
                var metadata = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(rs.getString("metadata_json"));
                metadata.put("occurred_at",rs.getObject("occurred_at",OffsetDateTime.class).toInstant().toString());
                metadata.put("observed_at",rs.getObject("observed_at",OffsetDateTime.class).toInstant().toString());
                metadata.put("client_product",rs.getString("client_product"));
                return new SkillInvocationItem(rs.getLong("id"),rs.getString("center_user_id"),
                    rs.getObject("center_skill_id",Long.class),rs.getObject("received_at",OffsetDateTime.class).toInstant(),
                    mapper.treeToValue(metadata,SkillInvocationInput.class));
            } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted invocation metadata",e); }
        });
        return new PageResponse<>(items,total == null ? 0 : total,page,size);
    }
    private static void add(StringBuilder where, MapSqlParameterSource p, String column, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) return;
        where.append(" AND ").append(column).append(" = :").append(column);
        p.addValue(column,value instanceof String s ? s.trim() : value);
    }
}
