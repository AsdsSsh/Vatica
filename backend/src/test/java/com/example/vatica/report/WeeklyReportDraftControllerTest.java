package com.example.vatica.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WeeklyReportDraftControllerTest {

    @Test
    void createsAndUpdatesDraftContract() throws Exception {
        WeeklyReportDraftService service = mock(WeeklyReportDraftService.class);
        WeeklyReportDraftService.WeeklyReportDraftView view = view();
        when(service.create(any())).thenReturn(view);
        when(service.update(any(), any())).thenReturn(view);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportDraftController(service)).build();

        mvc.perform(post("/api/weekly-reports/drafts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-08-24\",\"to\":\"2026-08-30\",\"reportType\":\"WEEKLY\","
                        + "\"includeCalendar\":true,\"includeTodos\":true,\"title\":\"周报\","
                        + "\"wordRequested\":true,\"excelRequested\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("draft-1"))
                .andExpect(jsonPath("$.wordPreview").value("# 周报"))
                .andExpect(jsonPath("$.facts.statistics.meetingCount").value(1));

        mvc.perform(patch("/api/weekly-reports/drafts/draft-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"周报\",\"focus\":\"重点\",\"wordRequested\":true,\"excelRequested\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    private static WeeklyReportDraftService.WeeklyReportDraftView view() {
        WeeklyReportService.WeeklyReportView facts = new WeeklyReportService.WeeklyReportView("weekly:key", "WEEKLY",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 30), List.of(), List.of(), List.of(),
                new WeeklyReportService.Statistics(1, 0, 0, 0, 0), "", List.of(), "2026-08-21T00:00:00Z");
        return new WeeklyReportDraftService.WeeklyReportDraftView("draft-1", "DRAFT", "周报", "重点", "", "",
                true, true, facts, "# 周报", "指标,数值", List.of(), "2026-08-21T00:00:00Z",
                "2026-08-21T00:00:00Z");
    }
}
