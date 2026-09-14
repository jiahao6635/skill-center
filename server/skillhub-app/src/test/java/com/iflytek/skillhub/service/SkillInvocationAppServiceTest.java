package com.iflytek.skillhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.invocation.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillInvocationAppServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final SkillInvocationRepository repository=mock(SkillInvocationRepository.class);
    final SkillInvocationAppService service=new SkillInvocationAppService(repository);
    static final String EVENT="""
        {"source":"qoder-request-logger","event_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "email":"  Example@Company.test ","session_id":"s1","skill_name":"plugin:local",
         "occurred_at":"2026-09-14T01:00:00Z","observed_at":"2026-09-14T01:01:00Z",
         "time_source":"source","trigger_mode":"automatic","evidence":"transcript_tool_use","client_product":"qoderwork",
         "prompt":"MUST-NOT-PERSIST","uid":"external-id"}
        """;
    @Test void normalizesIdentityAndDropsConversationContents() throws Exception {
        when(repository.save(any())).thenAnswer(call->{
            SkillInvocation event=call.getArgument(0);
            assertThat(event.email()).isEqualTo("example@company.test");
            assertThat(event.metadata()).doesNotContainKey("prompt");
            assertThat(event.metadata().get("uid")).isEqualTo("external-id");
            assertThat(event.metadata()).doesNotContainKey("skill_coordinate");
            return SkillInvocationRepository.Outcome.ACCEPTED;
        });
        assertThat(service.ingest(List.of(mapper.readTree(EVENT))).results().getFirst().status()).isEqualTo("accepted");
    }
    @Test void isolatesPoisonItemsAndAcknowledgesDuplicates() throws Exception {
        when(repository.save(any())).thenReturn(SkillInvocationRepository.Outcome.DUPLICATE);
        var result=service.ingest(List.of(mapper.readTree("{}"),mapper.readTree(EVENT)));
        assertThat(result.results()).extracting(r->r.status()).containsExactly("rejected","duplicate");
        verify(repository,times(1)).save(any());
    }
    @Test void storageOutageFailsRequestForReplay() throws Exception {
        when(repository.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(()->service.ingest(List.of(mapper.readTree(EVENT)))).isInstanceOf(IllegalStateException.class);
    }
    @Test void conflictIsPermanentAndOversizedMetadataRejected() throws Exception {
        when(repository.save(any())).thenReturn(SkillInvocationRepository.Outcome.CONFLICT);
        var event=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(EVENT);
        assertThat(service.ingest(List.of(event)).results().getFirst().error()).isEqualTo("EVENT_ID_CONFLICT");
        event.put("skill_name","a".repeat(513));
        assertThat(service.ingest(List.of(event)).results().getFirst().error()).isEqualTo("INVALID_EVENT");
    }
}
