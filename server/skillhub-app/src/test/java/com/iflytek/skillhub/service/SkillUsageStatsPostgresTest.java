package com.iflytek.skillhub.service;

import com.iflytek.skillhub.repository.SkillUsageStatsQueryRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SKILL_INVOCATION_TEST_JDBC_URL", matches = ".+")
class SkillUsageStatsPostgresTest {
    private JdbcTemplate jdbc;
    private SkillUsageStatsAppService service;
    private String schema;
    private static final Instant FROM = Instant.parse("2026-09-13T16:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-14T16:00:00Z");

    @BeforeEach void setup() {
        String url = System.getenv("SKILL_INVOCATION_TEST_JDBC_URL");
        schema = "skill_stats_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url)).execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_account(id varchar(128) PRIMARY KEY, display_name text)");
        jdbc.execute("CREATE TABLE skill(id bigint PRIMARY KEY, download_count bigint, star_count integer)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V49__skill_invocations.sql")).execute(ds);
        jdbc.update("INSERT INTO user_account VALUES ('a', '中心姓名')");
        jdbc.update("INSERT INTO skill VALUES (1, 300, 12), (2, 500, 20)");
        service = new SkillUsageStatsAppService(new SkillUsageStatsQueryRepository(new NamedParameterJdbcTemplate(ds)));
        event("1", "a@test", "same-session", "plugin:alpha", "qoder_ide", FROM, "a", 1L, "旧姓名", "automatic");
        event("2", "a@test", "same-session", "plugin:alpha", "qoderwork", FROM.plusSeconds(1), null, null, "源姓名", "manual");
        event("3", "b@test", "same-session", "beta", "unknown", FROM.plusSeconds(2), null, null, "同名", "manual");
        event("4", "b@test", "second", "plugin:alpha", "unknown", FROM.plusSeconds(3), null, 1L, "", "automatic");
        event("5", "c@test", "old", "old", "qoder", FROM.minusSeconds(1), null, null, "同名", "automatic");
        event("6", "c@test", "future", "future", "qoder", TO, null, null, "", "automatic");
    }
    private void event(String id, String email, String session, String skill, String product, Instant at,
                       String userId, Long skillId, String name, String mode) {
        jdbc.update("""
            INSERT INTO skill_invocation_event(source,event_id,email,session_id,skill_name,client_product,
              occurred_at,observed_at,time_source,evidence,center_user_id,center_skill_id,metadata_json)
            VALUES ('qoder-request-logger',?,?,?,?,?,?::timestamptz,?::timestamptz,'source','hook',?,?,
              jsonb_build_object('name',?::text,'trigger_mode',?::text)) ON CONFLICT(source,event_id) DO NOTHING
            """, id, email, session, skill, product, at.toString(), at.toString(), userId, skillId, name, mode);
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void summaryCountsInvocationsAndCompositeSessionsWithinBeijingDay() {
        event("1", "a@test", "same-session", "plugin:alpha", "qoder_ide", FROM, "a", 1L, "", "automatic");
        var result = service.summary(FROM, TO, null, null);
        assertThat(result.invocationCount()).isEqualTo(4);
        assertThat(result.userCount()).isEqualTo(2);
        assertThat(result.skillCount()).isEqualTo(2);
        assertThat(result.sessionCount()).isEqualTo(3);
        assertThat(service.summary(FROM, TO, " A@TEST ", null).invocationCount()).isEqualTo(2);
        assertThat(service.summary(FROM, TO, null, "unknown").invocationCount()).isEqualTo(2);
        assertThat(service.summary(FROM, TO, "nobody@test", null).invocationCount()).isZero();
    }
    @Test void skillRankingSearchPaginationAndRegistryCountersUseSameLedger() {
        var ranks = service.skills(FROM, TO, null, null, "", 0, 1);
        assertThat(ranks.total()).isEqualTo(2);
        var first = ranks.items().getFirst();
        assertThat(first.skillName()).isEqualTo("plugin:alpha");
        assertThat(first.invocationCount()).isEqualTo(3);
        assertThat(first.userCount()).isEqualTo(2);
        assertThat(first.downloadCount()).isEqualTo(300);
        assertThat(first.starCount()).isEqualTo(12);
        var second = service.skills(FROM, TO, null, null, "", 1, 1).items().getFirst();
        assertThat(second.rank()).isEqualTo(2);
        assertThat(second.unlinked()).isTrue();
        assertThat(second.downloadCount()).isNull();
        assertThat(second.peakCount()).isEqualTo(3);
        assertThat(service.skills(FROM, TO, null, null, "BETA", 0, 20).items().getFirst().peakCount()).isEqualTo(3);
        assertThat(service.skills(FROM, TO, null, null, "%", 0, 20).total()).isZero();
        var personal = service.skills(FROM, TO, "a@test", null, null, 0, 20).items().getFirst();
        assertThat(personal.invocationCount()).isEqualTo(2);
        assertThat(personal.downloadCount()).isEqualTo(300);
        event("7", "a@test", "different", "plugin:alpha", "qoder", FROM.plusSeconds(10), null, 2L, "", "automatic");
        assertThat(service.skills(FROM, TO, null, null, "alpha", 0, 20).items().getFirst().downloadCount()).isNull();
    }
    @Test void userNamesAndSearchIncludeUnregisteredUsersOutsideDateWindow() {
        var users = service.users(FROM, TO, null, null, 0, 20);
        assertThat(users.items()).extracting(r -> r.email()).containsExactly("a@test", "b@test");
        assertThat(users.items().getFirst().name()).isEqualTo("中心姓名");
        assertThat(users.items().get(1).name()).isEqualTo("同名");
        assertThat(users.items().get(1).skillCount()).isEqualTo(2);
        assertThat(service.userOptions("同名")).extracting(r -> r.email()).containsExactly("b@test", "c@test");
        assertThat(service.userOptions("A@TEST")).hasSize(1);
        assertThat(service.userOptions("%'")).isEmpty();
    }
    @Test void stableTiesAndBoundedUserOptions() {
        event("tie", "b@test", "second", "Beta", "qoder", FROM.plusSeconds(4), null, null, "", "automatic");
        var ranks = service.skills(FROM, TO, null, null, "", 0, 20);
        assertThat(ranks.items()).extracting(r -> r.skillName()).containsExactly("plugin:alpha", "Beta", "beta");
        for (int i = 0; i < 25; i++) event("extra" + i, "extra" + i + "@test", "s", "a", "qoder", FROM, null, null, "", "manual");
        assertThat(service.userOptions("extra")).hasSize(20);
    }
    @Test void validationRejectsExpensiveOrInvalidRanges() {
        assertThatThrownBy(() -> service.summary(FROM, FROM, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(FROM, FROM.plusSeconds(367L * 86400), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(FROM, TO, null, "invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.skills(FROM, TO, null, null, null, -1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.users(FROM, TO, null, null, 0, 201)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SKILL_USAGE_EXPLAIN", matches = "1")
    void inspectRepresentativeQueryPlans() {
        jdbc.execute("""
            INSERT INTO skill_invocation_event(source,event_id,email,session_id,skill_name,client_product,
              occurred_at,observed_at,time_source,evidence,metadata_json)
            SELECT 'qoder-request-logger','perf-' || n, 'user' || n % 500 || '@test', 's-' || n / 5,
              'skill-' || n % 100, 'qoder_ide', timestamptz '2026-08-16 00:00:00+08' + (n % 30) * interval '1 day',
              now(), 'source','hook',jsonb_build_object('name', 'User ' || n % 500)
            FROM generate_series(1,100000) n
            """);
        jdbc.execute("ANALYZE skill_invocation_event");
        for (String where : List.of("", " AND email='user42@test'")) {
            var plan = jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS) SELECT count(*),count(DISTINCT email),count(DISTINCT skill_name),
                count(DISTINCT (email,session_id)) FROM skill_invocation_event
                WHERE occurred_at >= timestamptz '2026-08-16 00:00:00+08'
                  AND occurred_at < timestamptz '2026-09-15 00:00:00+08'
                """ + where, String.class);
            System.out.println("SKILL_USAGE_PLAN " + where + "\n" + String.join("\n", plan));
        }
        var start = Instant.parse("2026-08-15T16:00:00Z");
        long before = System.nanoTime();
        assertThat(service.skills(start, TO, null, null, "", 0, 20).items()).hasSize(20);
        System.out.println("SKILL_USAGE_SKILLS_MS=" + (System.nanoTime() - before) / 1_000_000);
        before = System.nanoTime();
        assertThat(service.users(start, TO, null, null, 0, 20).items()).hasSize(20);
        System.out.println("SKILL_USAGE_USERS_MS=" + (System.nanoTime() - before) / 1_000_000);
        before = System.nanoTime();
        assertThat(service.userOptions("User")).hasSize(20);
        System.out.println("SKILL_USAGE_OPTIONS_MS=" + (System.nanoTime() - before) / 1_000_000);
    }
}
