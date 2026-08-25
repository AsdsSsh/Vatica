package com.example.vatica.context;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 迭代 29D：健康 API 的脱敏字段契约。 */
class ContextHealthControllerTest {

    @Test
    void exposesStatusAndWatermarksWithoutRawContext() throws Exception {
        ContextHealthService service = mock(ContextHealthService.class);
        when(service.get(ContextFactScopeType.CHAT_SESSION, "s1")).thenReturn(new ContextHealthView(
                "CHAT_SESSION", "s1", ContextHealthStatus.DEGRADED,
                com.example.vatica.controller.SessionSummaryStatus.FAILED,
                com.example.vatica.controller.SessionSummaryFailureCode.EMPTY_RESPONSE,
                8, 12, 4, 2, 2, 4, 1, Instant.parse("2026-08-26T08:00:00Z"), null, null,
                3, 1, false, "SUMMARY_FAILED_EMPTY_RESPONSE", Instant.parse("2026-08-26T08:01:00Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContextHealthController(service)).build();

        mvc.perform(get("/api/context/health").param("scopeId", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.summaryThroughSeq").value(8))
                .andExpect(jsonPath("$.staleFactCount").value(1))
                .andExpect(jsonPath("$.summaryText").doesNotExist())
                .andExpect(jsonPath("$.valueJson").doesNotExist())
                .andExpect(jsonPath("$.evidenceRefsJson").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.orgId").doesNotExist());
    }

    @Test
    void supportsTaskGateScope() throws Exception {
        ContextHealthService service = mock(ContextHealthService.class);
        when(service.get(ContextFactScopeType.TASK, "t1")).thenReturn(new ContextHealthView(
                "TASK", "t1", ContextHealthStatus.DEGRADED, null, null,
                0, 0, 0, 0, 0, 0, 0, null, null, null, 0, 0,
                true, "CONTEXT_GATE_PENDING", Instant.now()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ContextHealthController(service)).build();

        mvc.perform(get("/api/context/health").param("scopeType", "TASK").param("scopeId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeType").value("TASK"))
                .andExpect(jsonPath("$.contextGatePending").value(true));
    }
}
