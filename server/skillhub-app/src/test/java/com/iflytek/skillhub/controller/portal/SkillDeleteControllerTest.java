package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.TestRedisConfig;
import com.iflytek.skillhub.auth.device.DeviceAuthService;
import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.domain.namespace.NamespaceMemberRepository;
import com.iflytek.skillhub.service.SkillDeleteAppService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class SkillDeleteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SkillDeleteAppService skillDeleteAppService;

    @MockBean
    private NamespaceMemberRepository namespaceMemberRepository;

    @MockBean
    private DeviceAuthService deviceAuthService;

    @Test
    void deleteSkill_rejectsBearerTokenEvenForSuperAdmin() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
                "super-1", "Super", "super@example.com", "", "api_token", Set.of("SUPER_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_skill:delete")
                ));

        mockMvc.perform(delete("/api/v1/skills/global/demo-skill")
                        .with(authentication(auth))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deleteSkill_rejectsNonSuperAdmin() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
                "skill-1", "Skill Admin", "skill@example.com", "", "api_token", Set.of("SKILL_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SKILL_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_skill:delete")
                ));

        mockMvc.perform(delete("/api/v1/skills/global/demo-skill")
                        .with(authentication(auth))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deleteSkillById_rejectsBearerTokenEvenForSuperAdmin() throws Exception {
        PlatformPrincipal principal = new PlatformPrincipal(
                "super-1", "Super", "super@example.com", "", "api_token", Set.of("SUPER_ADMIN"));
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_skill:delete")
                ));

        mockMvc.perform(delete("/api/v1/skills/id/11")
                        .with(authentication(auth))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
