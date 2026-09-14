package com.iflytek.skillhub.repository;

import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.SkillUsageStats.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/** PostgreSQL read model: database aggregation and JSONB name fallback avoid loading the ledger
 * into application memory. Repeatable reads keep count/page queries on the same snapshot. */
@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SkillUsageStatsQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // A linked account's current name takes precedence; otherwise use the latest nonblank source
    // name. All history is eligible, even when the current reporting period has no name metadata.
    private static final String NAME_JOIN = """
        LEFT JOIN LATERAL (
          SELECT COALESCE(NULLIF(btrim(u.display_name), ''), NULLIF(btrim(e.metadata_json->>'name'), '')) AS name
          FROM skill_invocation_event e LEFT JOIN user_account u ON u.id = e.center_user_id
          WHERE e.email = r.email
            AND COALESCE(NULLIF(btrim(u.display_name), ''), NULLIF(btrim(e.metadata_json->>'name'), '')) IS NOT NULL
          ORDER BY (NULLIF(btrim(u.display_name), '') IS NOT NULL) DESC, e.occurred_at DESC, e.id DESC
          LIMIT 1
        ) identity ON true
        """;

    public SkillUsageStatsQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private record Filter(String sql, MapSqlParameterSource params) { }

    private Filter filter(Instant from, Instant to, String email, String product) {
        var p = new MapSqlParameterSource()
                .addValue("from", from.atOffset(ZoneOffset.UTC)).addValue("to", to.atOffset(ZoneOffset.UTC));
        var where = new StringBuilder(" WHERE occurred_at >= :from AND occurred_at < :to");
        if (email != null && !email.isBlank()) {
            where.append(" AND email = :email");
            p.addValue("email", email.trim().toLowerCase(Locale.ROOT));
        }
        if (product != null && !product.isBlank()) {
            where.append(" AND client_product = :product");
            p.addValue("product", product);
        }
        return new Filter(where.toString(), p);
    }

    public Summary summary(Instant from, Instant to, String email, String product) {
        var f = filter(from, to, email, product);
        return jdbc.queryForObject("""
            SELECT count(*) AS invocations, count(DISTINCT email) AS users,
                   count(DISTINCT skill_name) AS skills, count(DISTINCT (email, session_id)) AS sessions
            FROM skill_invocation_event
            """ + f.sql(), f.params(), (rs, n) -> new Summary(rs.getLong("invocations"), rs.getLong("users"),
                rs.getLong("skills"), rs.getLong("sessions"), Instant.now()));
    }

    public PageResponse<SkillRank> skills(Instant from, Instant to, String email, String product,
                                         String search, int page, int size) {
        var f = filter(from, to, email, product);
        var p = f.params().addValue("search", search == null ? "" : search.trim())
                .addValue("limit", size).addValue("offset", (long) page * size);
        String cte = """
            WITH ranked AS (
              SELECT skill_name, count(*) AS calls, count(DISTINCT email) AS users,
                     max(occurred_at) AS last_used, bool_and(center_skill_id IS NULL) AS unlinked,
                     CASE WHEN count(DISTINCT center_skill_id) = 1 THEN min(center_skill_id) END AS center_id
              FROM skill_invocation_event
            """ + f.sql() + " GROUP BY skill_name), matched AS (SELECT * FROM ranked WHERE strpos(lower(skill_name), lower(:search)) > 0) ";
        long total = jdbc.queryForObject(cte + "SELECT count(*) FROM matched", p, Long.class);
        var items = jdbc.query(cte + """
            SELECT m.*, s.download_count, s.star_count, row_number() OVER (ORDER BY calls DESC, skill_name COLLATE "C") AS rank,
                   (SELECT max(calls) FROM ranked) AS peak
            FROM matched m LEFT JOIN skill s ON s.id = m.center_id
            ORDER BY calls DESC, skill_name COLLATE "C" LIMIT :limit OFFSET :offset
            """, p, (rs, n) -> new SkillRank(rs.getString("skill_name"), rs.getLong("calls"), rs.getLong("users"),
                rs.getObject("last_used", OffsetDateTime.class).toInstant(), rs.getBoolean("unlinked"),
                rs.getLong("rank"), rs.getLong("peak"), rs.getObject("download_count", Long.class), rs.getObject("star_count", Integer.class)));
        return new PageResponse<>(items, total, page, size);
    }

    public PageResponse<UserRank> users(Instant from, Instant to, String email, String product, int page, int size) {
        var f = filter(from, to, email, product);
        var p = f.params().addValue("limit", size).addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("SELECT count(DISTINCT email) FROM skill_invocation_event" + f.sql(), p, Long.class);
        String cte = """
            WITH ranked AS (
              SELECT email, count(*) AS calls, count(DISTINCT skill_name) AS skills, max(occurred_at) AS last_used
              FROM skill_invocation_event
            """ + f.sql() + " GROUP BY email), paged AS (SELECT *, row_number() OVER (ORDER BY calls DESC, email COLLATE \"C\") AS rank FROM ranked ORDER BY calls DESC, email COLLATE \"C\" LIMIT :limit OFFSET :offset) ";
        var items = jdbc.query(cte + "SELECT r.*, COALESCE(identity.name, '') AS name FROM paged r " + NAME_JOIN
                + " ORDER BY r.rank", p, (rs, n) -> new UserRank(rs.getString("email"), rs.getString("name"),
                rs.getLong("calls"), rs.getLong("skills"), rs.getObject("last_used", OffsetDateTime.class).toInstant(), rs.getLong("rank")));
        return new PageResponse<>(items, total, page, size);
    }

    public List<UserOption> userOptions(String search) {
        // Literal substring matching: % and _ in user input are not SQL wildcards.
        return jdbc.query("SELECT r.email, COALESCE(identity.name, '') AS name FROM (SELECT DISTINCT email FROM skill_invocation_event) r "
                + NAME_JOIN + """
                WHERE strpos(lower(r.email), lower(:search)) > 0 OR strpos(lower(COALESCE(identity.name, '')), lower(:search)) > 0
                ORDER BY r.email COLLATE "C" LIMIT 20
                """, new MapSqlParameterSource("search", search == null ? "" : search.trim()),
                (rs, n) -> new UserOption(rs.getString("email"), rs.getString("name")));
    }
}
