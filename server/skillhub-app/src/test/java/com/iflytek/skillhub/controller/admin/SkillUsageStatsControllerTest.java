package com.iflytek.skillhub.controller.admin;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.repository.SkillUsageStatsQueryRepository;
import com.iflytek.skillhub.dto.PageResponse;
import com.iflytek.skillhub.dto.SkillUsageStats.Summary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillUsageStatsControllerTest {
    @Autowired MockMvc mvc;
    @MockBean NamespaceMemberRepository namespaces;
    @MockBean DeviceAuthService deviceAuth;
    @MockBean SkillUsageStatsQueryRepository repository;
    private static final String BASE = "/api/v1/admin/skill-invocations/";
    private static final String DATES = "?from=2026-09-13T16:00:00Z&to=2026-09-14T16:00:00Z";

    @Test void everyReadEndpointRequiresSuperAdmin() throws Exception {
        when(repository.summary(any(), any(), any(), any())).thenReturn(new Summary(5, 2, 3, 4, Instant.now()));
        when(repository.skills(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));
        when(repository.users(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));
        when(repository.userOptions(any())).thenReturn(List.of());
        for (String endpoint : List.of("summary", "skills", "users", "user-options")) {
            String url = BASE + endpoint + DATES;
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("X-Skill-Usage-Key", "test-key")).andExpect(status().isUnauthorized());
            for (String role : List.of("USER", "AUDITOR", "SKILL_ADMIN", "USER_ADMIN"))
                mvc.perform(get(url).with(user("reader").roles(role))).andExpect(status().isForbidden());
            mvc.perform(get(url).with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isOk());
        }
    }
    @Test void invalidParametersFailBeforeQuerying() throws Exception {
        mvc.perform(get(BASE + "summary").with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "summary" + DATES + "&product=invalid").with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "skills" + DATES + "&page=-1").with(user("admin").roles("SUPER_ADMIN"))).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
