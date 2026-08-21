package com.example.vatica.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.vatica.action.ActionPlanView;

class WeeklyReportExportControllerTest {

    @Test
    void exposesPrepareAndApproveContract() throws Exception {
        WeeklyReportExportService service = mock(WeeklyReportExportService.class);
        WeeklyReportExportService.WeeklyReportExportView view = view();
        when(service.prepare(any(), any())).thenReturn(view);
        when(service.approve("export-1")).thenReturn(view);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeeklyReportExportController(service)).build();

        String body = "{\"wordRequested\":true,\"excelRequested\":true,\"mailRequested\":true,"
                + "\"mailTo\":\"owner@example.com\",\"mailSubject\":\"本周周报\"}";
        mvc.perform(post("/api/weekly-reports/drafts/draft-1/exports").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.actionPlan.actions.length()").value(3));
        mvc.perform(post("/api/weekly-reports/exports/export-1/approve")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actionPlan.subjectType").value("WEEKLY_REPORT"));
    }

    private static WeeklyReportExportService.WeeklyReportExportView view() {
        ActionPlanView plan = ActionPlanView.weeklyReportExport("draft-1", "本周周报", true, true, true,
                "owner@example.com", "report.docx", "report.xlsx", "mail.md", "DRAFT");
        return new WeeklyReportExportService.WeeklyReportExportView("export-1", "draft-1", "DRAFT", plan,
                "owner@example.com", "本周周报", "# 本周周报", List.of(), null, null, null);
    }
}
