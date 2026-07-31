package com.iflytek.skillhub.controller.portal;

import com.iflytek.skillhub.auth.rbac.PlatformPrincipal;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.service.SkillDownloadLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DownloadLinkControllerTest {

    @Mock
    private SkillDownloadLinkService skillDownloadLinkService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiResponseFactory responseFactory = new ApiResponseFactory(new StaticMessageSource(), Clock.systemUTC());
        DownloadLinkController controller = new DownloadLinkController(responseFactory, skillDownloadLinkService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter,
                                                  ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  WebDataBinderFactory binderFactory) {
                        return new PlatformPrincipal("user-1", "User One", "user@example.com", null, null, Set.of());
                    }
                })
                .build();
    }

    @Test
    void createDownloadLink_RedirectMode_ReturnsRedirectUrl() throws Exception {
        when(skillDownloadLinkService.issueDownloadLink("global", "demo-skill", null, "user-1", Map.of()))
                .thenReturn(SkillDownloadLinkService.IssueResult.redirect("tok-123", Instant.parse("2026-01-01T00:10:00Z")));

        mockMvc.perform(post("/api/web/skills/global/demo-skill/download-link")
                        .header("Host", "skill-center.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.downloadUrl").value("http://skill-center.test/api/cli/v1/download-link/tok-123"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-01-01T00:10:00Z"));
    }

    @Test
    void createDownloadLink_FallbackMode_ReturnsFallbackUrl() throws Exception {
        when(skillDownloadLinkService.issueDownloadLink("global", "demo-skill", "1.0.0", "user-1", Map.of()))
                .thenReturn(SkillDownloadLinkService.IssueResult.fallback(
                        "/api/cli/v1/skills/global/demo-skill/download", Instant.parse("2026-01-01T00:10:00Z")));

        mockMvc.perform(post("/api/web/skills/global/demo-skill/download-link")
                        .param("version", "1.0.0")
                        .header("Host", "skill-center.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").value("http://skill-center.test/api/cli/v1/skills/global/demo-skill/download"));
    }
}
