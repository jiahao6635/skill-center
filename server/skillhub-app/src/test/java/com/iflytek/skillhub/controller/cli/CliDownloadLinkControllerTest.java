package com.iflytek.skillhub.controller.cli;

import com.iflytek.skillhub.domain.shared.exception.DomainNotFoundException;
import com.iflytek.skillhub.dto.ApiResponseFactory;
import com.iflytek.skillhub.exception.GlobalExceptionHandler;
import com.iflytek.skillhub.metrics.SkillHubMetrics;
import com.iflytek.skillhub.security.SensitiveLogSanitizer;
import com.iflytek.skillhub.service.SkillDownloadLinkService;
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
    private SensitiveLogSanitizer sensitiveLogSanitizer;
    @Mock
    private SkillHubMetrics skillHubMetrics;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiResponseFactory responseFactory = new ApiResponseFactory(new StaticMessageSource(), Clock.systemUTC());
        CliDownloadLinkController controller = new CliDownloadLinkController(skillDownloadLinkService);
        GlobalExceptionHandler advice = new GlobalExceptionHandler(responseFactory, sensitiveLogSanitizer, skillHubMetrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .build();
    }

    @Test
    void redirect_ValidToken_Returns302WithPresignedLocation() throws Exception {
        when(skillDownloadLinkService.resolveForRedirect("tok-1")).thenReturn("https://oss.example/presigned");

        mockMvc.perform(get("/api/cli/v1/download-link/tok-1"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "https://oss.example/presigned"));

        verify(skillDownloadLinkService).resolveForRedirect("tok-1");
    }

    @Test
    void redirect_UnknownOrExpiredToken_Returns404() throws Exception {
        when(skillDownloadLinkService.resolveForRedirect("bad"))
                .thenThrow(new DomainNotFoundException("error.downloadLink.notFound"));

        mockMvc.perform(get("/api/cli/v1/download-link/bad"))
                .andExpect(status().isNotFound());
    }
}
