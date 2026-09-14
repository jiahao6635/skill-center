package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.invocation.SkillInvocationRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real plugin process -> log server HTTP/outbox -> center HTTP -> PostgreSQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="skillhub.skill-invocations.key-sha256=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef")
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
@EnabledIfEnvironmentVariable(named="SKILL_INVOCATION_TEST_JDBC_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="QODER_LOGGER_REPO",matches=".+")
class SkillInvocationPipelineTest {
    @LocalServerPort int centerPort;
    @MockBean NamespaceMemberRepository namespaces;
    @MockBean DeviceAuthService deviceAuth;
    @MockBean SkillInvocationRepository port;
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    SkillInvocationPostgresTest database;
    Process logger;
    int logPort;
    @BeforeEach void setup() throws Exception {
        database=new SkillInvocationPostgresTest(); database.setup();
        // Only the repository port is substituted: delegate to the real PostgreSQL adapter,
        // with the same commit-before-response boundary as its production transaction proxy.
        AtomicBoolean loseAck=new AtomicBoolean(true);
        when(port.save(any())).thenAnswer(call->{
            var result=database.save(call.getArgument(0));
            if(loseAck.getAndSet(false))throw new IllegalStateException("Simulated response failure AFTER database commit");
            return result;
        });
        try(var socket=new java.net.ServerSocket(0)){logPort=socket.getLocalPort();}
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("plugin-key".getBytes()));
        Files.writeString(temp.resolve("keys.yml"),"keys:\n  - user_id: pipeline@example.test\n    key_sha256: "+hash+"\n    enabled: true\n");
        startLogger();
    }
    @AfterEach void cleanup() throws Exception {
        stopLogger(); if(database!=null)database.cleanup();
    }
    void startLogger() throws Exception {
        Path repo=Path.of(System.getenv("QODER_LOGGER_REPO"));
        Path jar=repo.resolve("server/target/qoder-log-server-1.0.0.jar");
        assertThat(jar).exists();
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",jar.toString(),
            "--server.port="+logPort,"--audit.api-keys-file="+temp.resolve("keys.yml"),
            "--audit.spool-dir="+temp.resolve("spool"),"--audit.disk.high-watermark=1.0",
            "--oss.mode=file","--oss.file-storage-dir="+temp.resolve("oss"),"--skill-center.enabled=true",
            "--skill-center.url=http://127.0.0.1:"+centerPort,"--skill-center.outbox-dir="+temp.resolve("outbox"),
            "--skill-center.interval-ms=100");
        builder.environment().put("SKILL_CENTER_API_KEY","test-key");
        logger=builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(temp.resolve("logger.log").toFile())).start();
        var client=HttpClient.newHttpClient();
        await(()-> {
            try {return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+logPort+"/actuator/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
            catch(Exception e){return false;}
        });
    }
    void stopLogger() throws Exception {
        if(logger!=null){logger.destroy();if(!logger.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))logger.destroyForcibly();logger=null;}
    }
    long pending() {
        try(var paths=Files.list(temp.resolve("outbox"))){return paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}-[a-f0-9]{64}\\.json")).count();}
        catch(Exception e){return -1;}
    }
    @Test void actualPipelineSurvivesLostAcknowledgmentAndLoggerRestart() throws Exception {
        var event=mapper.createObjectNode().put("hook_event_name","PreToolUse").put("session_id","pipeline")
                .put("tool_name","Skill").put("tool_use_id","pipeline-call");
        event.putObject("tool_input").put("skill","external:demo").put("args","PRIVATE-CONVERSATION");
        event.putObject("extra").put("request_time","2026-09-14T01:00:00Z").putObject("user").put("email","new@example.test");
        event.putObject("parent_business_info").put("product","qoder_work");
        var builder=new ProcessBuilder("node",Path.of(System.getenv("QODER_LOGGER_REPO"),"plugin/hooks/log-request.js").toString());
        builder.environment().putAll(Map.of("QODER_LOG_DIR",temp.resolve("client").toString(),
                "QODER_LOG_SERVER_URL","http://127.0.0.1:"+logPort,"QODER_LOG_API_KEY","plugin-key",
                "QODER_LOG_UPLOAD_MODE","cursor","QODER_LOG_UPLOAD_INTERVAL_SEC","0"));
        Process plugin=builder.redirectErrorStream(true).redirectOutput(temp.resolve("plugin.log").toFile()).start();
        try(var input=plugin.getOutputStream()){input.write(event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertThat(plugin.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(plugin.exitValue()).isZero();
        await(()->database.jdbc.queryForObject("SELECT count(*) FROM skill_invocation_event",Long.class)==1);
        await(()->Files.exists(temp.resolve("outbox/state.json")));
        assertThat(pending()).isEqualTo(1);
        stopLogger();
        // Advance the persisted retry clock in this fixture instead of sleeping through backoff.
        var state=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(temp.resolve("outbox/state.json")));
        state.put("next_attempt_ms",0);Files.writeString(temp.resolve("outbox/state.json"),state.toString());
        startLogger(); await(()->pending()==0);
        var records=database.query.list(0,50,"new@example.test",null,null,null,"qoderwork",null,null,null);
        assertThat(records.total()).isEqualTo(1);
        var saved=records.items().getFirst();
        assertThat(saved.centerUserId()).isNull();assertThat(saved.centerSkillId()).isNull();
        assertThat(saved.event().skillName()).isEqualTo("external:demo");
        assertThat(saved.event().occurredAt()).isEqualTo("2026-09-14T01:00:00Z");
        assertThat(mapper.writeValueAsString(saved)).doesNotContain("PRIVATE-CONVERSATION");
    }
    void await(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        while(System.nanoTime()<deadline){if(ready.getAsBoolean())return;Thread.sleep(100);}
        throw new AssertionError("Pipeline did not converge. Log: "+Files.readString(temp.resolve("logger.log")));
    }
}
