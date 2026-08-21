package com.example.vatica.meeting;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.vatica.action.ActionPlanView;
/** 迭代 24A：会议准备 API 的结构化输入与输出契约。 */
class MeetingPreparationControllerTest {

    @Test
    void listsCandidatesAndCreatesOnlyFromAnExplicitEventId() throws Exception {
        MeetingPreparationService service = mock(MeetingPreparationService.class);
        MeetingPreparationService.MeetingCandidate candidate =
                new MeetingPreparationService.MeetingCandidate(11L, "项目周会", "2026-08-24T09:30", "2026-08-24T10:30");
        MeetingPreparationService.MeetingPreparationDraft preview = new MeetingPreparationService.MeetingPreparationDraft(
                candidate, "准备决策项", List.of(), "NOT_REQUESTED", "仅基于日历和用户输入。", List.of(),
                List.of("确认决策项"), List.of("补充参会者"), List.of(), "# 项目周会");
        MeetingPreparationService.MeetingPreparationView draft = new MeetingPreparationService.MeetingPreparationView(
                "prep-1", "DRAFT", candidate, "准备决策项", true, "2026-08-20T10:00:00Z",
                "2026-08-20T10:00:00Z", preview, null, List.of(), null, null,
                ActionPlanView.meetingPreparation("prep-1", "项目周会", null,
                        List.of("确认决策项"), List.of(), "DRAFT"));
        when(service.candidates("2026-08-24", "2026-08-24", "周会")).thenReturn(List.of(candidate));
        when(service.create(11L, "准备决策项", true)).thenReturn(draft);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeetingPreparationController(service)).build();

        mvc.perform(get("/api/meeting-preparations/candidates")
                        .param("from", "2026-08-24").param("to", "2026-08-24").param("topic", "周会"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(11))
                .andExpect(jsonPath("$[0].title").value("项目周会"));
        mvc.perform(post("/api/meeting-preparations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendarEventId\":11,\"goal\":\"准备决策项\",\"includeKnowledge\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.meeting.eventId").value(11))
                .andExpect(jsonPath("$.draft.knowledgeStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.actionPlan.actions[0].approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.actionPlan.actions[0].idempotencyKey").value("meeting-preparation:prep-1:document"))
                .andExpect(jsonPath("$.todoIds").isArray());

        verify(service).candidates("2026-08-24", "2026-08-24", "周会");
        verify(service).create(eq(11L), eq("准备决策项"), eq(true));
    }

    @Test
    void approveAndRejectKeepExplicitSideEffectDecisionsInTheHttpContract() throws Exception {
        MeetingPreparationService service = mock(MeetingPreparationService.class);
        MeetingPreparationService.MeetingCandidate candidate =
                new MeetingPreparationService.MeetingCandidate(11L, "项目周会", "2026-08-24T09:30", "2026-08-24T10:30");
        MeetingPreparationService.MeetingPreparationView applied = new MeetingPreparationService.MeetingPreparationView(
                "prep-1", "APPLIED", candidate, null, false, null, null, null,
                "meeting-preparation-prep-1.md", List.of("todo-a"), null, null);
        MeetingPreparationService.MeetingPreparationView rejected = new MeetingPreparationService.MeetingPreparationView(
                "prep-1", "REJECTED", candidate, null, false, null, null, null,
                null, List.of(), "范围需要补充", null);
        when(service.approve("prep-1")).thenReturn(applied);
        when(service.reject("prep-1", "范围需要补充")).thenReturn(rejected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeetingPreparationController(service)).build();

        mvc.perform(post("/api/meeting-preparations/prep-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.todoIds[0]").value("todo-a"));
        mvc.perform(post("/api/meeting-preparations/prep-1/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"范围需要补充\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("范围需要补充"));

        verify(service).approve("prep-1");
        verify(service).reject("prep-1", "范围需要补充");
    }
}
