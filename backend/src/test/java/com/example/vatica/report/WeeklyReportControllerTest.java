package com.example.vatica.report;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeeklyReportControllerTest {

    @Test
    void exposesFactsContractWithoutWriteAction() throws Exception {
        WeeklyReportService service = mock(WeeklyReportService.class);
        WeeklyReportService.WeeklyReportView view = new WeeklyReportService.WeeklyReportView(
                "weekly:2026-08-24:2026-08-30:WEEKLY", "WEEKLY", LocalDate.of(2026, 8, 24),
                LocalDate.of(2026, 8, 30), List.of(), List.of(), List.of(),
                new WeeklyReportService.Statistics(0, 0, 0, 0, 0), "", List.of("未选择资料"), "2026-08-21T00:00:00Z");
        when(service.collect(org.mockito.ArgumentMatchers.any())).thenReturn(view);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportController(service)).build();

        mvc.perform(post("/api/weekly-reports/facts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-08-24\",\"to\":\"2026-08-30\",\"reportType\":\"WEEKLY\","
                        + "\"includeCalendar\":true,\"includeTodos\":true,\"includeKnowledge\":false,\"userNotes\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportType").value("WEEKLY"))
                .andExpect(jsonPath("$.statistics.meetingCount").value(0))
                .andExpect(jsonPath("$.warnings[0]").value("未选择资料"));
    }
}
