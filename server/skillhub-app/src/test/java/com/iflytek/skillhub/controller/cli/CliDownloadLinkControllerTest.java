package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.exception.GlobalExceptionHandler;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import com.iflytek.skillhub.service.SkillDownloadLinkService;
import com.iflytek.skillhub.usage.UsageContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CliDownloadLinkControllerTest {

    @Mock
    private SkillDownloadLinkService skillDownloadLinkService;
    @Mock
    private UsageContextFactory usageContextFactory;
    @Mock
    private SensitiveLogSanitizer sensitiveLogSanitizer;
    @Mock
    private SkillHubMetrics skillHubMetrics;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiResponseFactory responseFactory = new ApiResponseFactory(new StaticMessageSource(), Clock.systemUTC());
        CliDownloadLinkController controller = new CliDownloadLinkController(skillDownloadLinkService, usageContextFactory);
        GlobalExceptionHandler advice = new GlobalExceptionHandler(responseFactory, sensitiveLogSanitizer, skillHubMetrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    @Test
    void redirect_ValidToken_Returns302WithPresignedLocation() throws Exception {
        when(usageContextFactory.fromRequest(any(), any())).thenReturn(null);
        when(skillDownloadLinkService.resolveForRedirect(eq("tok-1"), any())).thenReturn("https://oss.example/presigned");

        mockMvc.perform(get("/api/cli/v1/download-link/tok-1"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://oss.example/presigned"));

        verify(skillDownloadLinkService).resolveForRedirect(eq("tok-1"), any());
    }

    @Test
    void redirect_UnknownOrExpiredToken_Returns404() throws Exception {
        when(usageContextFactory.fromRequest(any(), any())).thenReturn(null);
        when(skillDownloadLinkService.resolveForRedirect(eq("bad"), any()))
                .thenThrow(new DomainNotFoundException("error.downloadLink.notFound"));

        mockMvc.perform(get("/api/cli/v1/download-link/bad"))
                .andExpect(status().isNotFound());
    }
}
