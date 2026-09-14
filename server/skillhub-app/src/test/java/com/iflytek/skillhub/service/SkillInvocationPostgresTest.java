package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.invocation.*;
import com.iflytek.skillhub.infra.repository.JdbcSkillInvocationRepository;
import com.iflytek.skillhub.repository.SkillInvocationQueryRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL regression: H2 cannot verify ON CONFLICT / JSONB / timestamptz semantics. */
@EnabledIfEnvironmentVariable(named="SKILL_INVOCATION_TEST_JDBC_URL",matches=".+")
class SkillInvocationPostgresTest {
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    JdbcSkillInvocationRepository repository;
    SkillInvocationQueryRepository query;
    TransactionTemplate transaction;
    final ObjectMapper mapper=new ObjectMapper();
    String schema;
    @BeforeEach void setup() {
        String url=System.getenv("SKILL_INVOCATION_TEST_JDBC_URL");
        schema="invocation_test_"+UUID.randomUUID().toString().replace("-","");
        ds=new DriverManagerDataSource(url);
        new JdbcTemplate(ds).execute("CREATE SCHEMA "+schema);
        ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema);
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE user_account(id varchar(128) primary key,email varchar(256))");
        jdbc.execute("CREATE TABLE namespace(id bigint primary key,slug varchar(64))");
        jdbc.execute("CREATE TABLE skill(id bigint primary key,namespace_id bigint,slug varchar(128))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V49__skill_invocations.sql")).execute(ds);
        jdbc.update("INSERT INTO user_account VALUES ('center-user','example@company.test')");
        jdbc.update("INSERT INTO namespace VALUES(1,'team'),(2,'other')");
        jdbc.update("INSERT INTO skill VALUES(10,1,'local'),(20,2,'local')");
        repository=new JdbcSkillInvocationRepository(new NamedParameterJdbcTemplate(ds),mapper);
        query=new SkillInvocationQueryRepository(new NamedParameterJdbcTemplate(ds),mapper);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void cleanup() { jdbc.execute("DROP SCHEMA "+schema+" CASCADE"); }
    SkillInvocation event(String email,String evidence,String timeSource,String time,String coordinate) throws Exception {
        var n=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(SkillInvocationAppServiceTest.EVENT);
        n.put("email",email).put("evidence",evidence).put("time_source",timeSource).put("occurred_at",time);
        if(coordinate!=null)n.put("skill_coordinate",coordinate);
        return SkillInvocationAppService.validate(n);
    }
    SkillInvocationRepository.Outcome save(SkillInvocation event) { return transaction.execute(s->repository.save(event)); }
    @Test void uniqueAcrossReplayAndEnrichmentPreservesRealTime() throws Exception {
        var early=event("example@company.test","hook","observed","2026-09-14T01:01:00Z",null);
        var late=event("example@company.test","transcript_tool_use","source","2026-09-14T01:00:00Z","@team/local");
        assertThat(save(early)).isEqualTo(SkillInvocationRepository.Outcome.ACCEPTED);
        assertThat(save(late)).isEqualTo(SkillInvocationRepository.Outcome.DUPLICATE);
        assertThat(save(early)).isEqualTo(SkillInvocationRepository.Outcome.DUPLICATE);
        var page=query.list(0,50,"EXAMPLE@COMPANY.TEST",null,null,null,null,null,null,null);
        assertThat(page.total()).isEqualTo(1);
        var item=page.items().getFirst();
        assertThat(item.centerUserId()).isEqualTo("center-user"); assertThat(item.centerSkillId()).isEqualTo(10L);
        assertThat(item.event().occurredAt()).isEqualTo("2026-09-14T01:00:00Z");
        assertThat(item.event().uid()).isEqualTo("external-id");
        assertThat(query.list(0,50,null,null,null,20L,null,null,null,null).total()).isZero();
    }
    @Test void unknownUserAndPluginNamespaceRemainUnlinked() throws Exception {
        save(event("unknown@company.test","hook","source","2026-09-14T01:00:00Z",null));
        var item=query.list(0,50,null,null,null,null,null,null,null,null).items().getFirst();
        assertThat(item.centerUserId()).isNull(); assertThat(item.centerSkillId()).isNull();
        assertThat(item.event().email()).isEqualTo("unknown@company.test");
    }
    @Test void concurrentRetriesCommitOneRowAndIdentityConflictIsRejected() throws Exception {
        var event=event("example@company.test","hook","source","2026-09-14T01:00:00Z",null);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var jobs=new ArrayList<Callable<SkillInvocationRepository.Outcome>>();
            for(int i=0;i<16;i++)jobs.add(()->save(event));
            var outcomes=new ArrayList<SkillInvocationRepository.Outcome>();
            for(var future:pool.invokeAll(jobs))outcomes.add(future.get());
            assertThat(outcomes.stream().filter(o->o==SkillInvocationRepository.Outcome.ACCEPTED).count()).isEqualTo(1);
        }
        assertThat(save(event("different@company.test","hook","source","2026-09-14T01:00:00Z",null)))
                .isEqualTo(SkillInvocationRepository.Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_invocation_event",Long.class)).isEqualTo(1L);
    }
}
