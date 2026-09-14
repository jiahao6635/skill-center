package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.domain.invocation.SkillInvocationRepository;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.service.SkillInvocationQueryAppService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.nio.file.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="skillhub.skill-invocations.key-sha256=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillInvocationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean NamespaceMemberRepository namespaces;
    @MockBean DeviceAuthService deviceAuth;
    @MockBean SkillInvocationRepository repository;
    @MockBean SkillInvocationQueryAppService query;
    static final String PATH="/api/internal/v1/skill-invocations/batch";
    static final String READ="/api/v1/admin/skill-invocations";
    @Test void internalEndpointRequiresDedicatedKeyEvenForAdmin() throws Exception {
        mvc.perform(post(PATH).with(user("admin").roles("SUPER_ADMIN")).contentType("application/json").content("[{}]"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("X-Skill-Usage-Key","wrong").contentType("application/json").content("[{}]"))
                .andExpect(status().isUnauthorized());
    }
    @Test void keyAllowsStatelessPostWithoutCsrfAndPoisonsArePerItem() throws Exception {
        mvc.perform(post(PATH).header("X-Skill-Usage-Key","test-key").contentType("application/json").content("[{},null]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.results[0].status").value("rejected"))
                .andExpect(jsonPath("$.data.results[1].status").value("rejected"));
    }
    @Test void malformedBodyIsPermanentBadRequest() throws Exception {
        mvc.perform(post(PATH).header("X-Skill-Usage-Key","test-key").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void batchBoundsReturnStandardBadRequest() throws Exception {
        mvc.perform(post(PATH).header("X-Skill-Usage-Key","test-key").contentType("application/json").content("[]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        String many="["+String.join(",",java.util.Collections.nCopies(501,"{}"))+"]";
        mvc.perform(post(PATH).header("X-Skill-Usage-Key","test-key").contentType("application/json").content(many))
                .andExpect(status().isBadRequest());
    }
    @Test void readIsRestrictedToAuditorAndSuperAdmin() throws Exception {
        when(query.list(anyInt(),anyInt(),any(),any(),any(),any(),any(),any(),any(),any()))
                .thenReturn(new PageResponse<>(List.of(),0,0,50));
        mvc.perform(get(READ)).andExpect(status().isUnauthorized());
        mvc.perform(get(READ).with(user("regular").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(READ).header("X-Skill-Usage-Key","test-key")).andExpect(status().isUnauthorized());
        for(String role:List.of("AUDITOR","SUPER_ADMIN"))
            mvc.perform(get(READ).with(user("reader").roles(role))).andExpect(status().isOk());
    }
    @Test void exportFullOpenApiWhenRequested() throws Exception {
        String file=System.getenv("SKILL_INVOCATION_OPENAPI_OUTPUT");
        if(file==null || file.isBlank()) return;
        String body=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Files.writeString(Path.of(file),body);
    }
}
