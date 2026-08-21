package com.example.vatica.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.action.ActionExecutionService;
import com.example.vatica.action.ActionPlanView;
import com.example.vatica.artifact.ArtifactService;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.permission.FilePermissionMode;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.permission.WorkspaceRoot;
import com.example.vatica.tool.DocumentTools;
import com.example.vatica.workspace.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;

class WeeklyReportExportServiceTest {

    private final WeeklyReportDraftService drafts = mock(WeeklyReportDraftService.class);
    private final WeeklyReportExportRepository repository = mock(WeeklyReportExportRepository.class);
    private final ActionExecutionService actions = mock(ActionExecutionService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final DocumentTools documents = mock(DocumentTools.class);
    private final WorkspaceStore workspace = mock(WorkspaceStore.class);
    private final PermissionPolicyService permissions = mock(PermissionPolicyService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WeeklyReportExportService service = new WeeklyReportExportService(drafts, repository, actions,
            artifacts, documents, workspace, permissions, mapper);
    private final RequestIdentity identity = new RequestIdentity(7L, 11L, "USER", "alice");

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void prepareFreezesDraftAndCreatesThreePendingActionsWithoutWriting() throws Exception {
        RequestIdentityContext.set(identity);
        WeeklyReportDraftService.WeeklyReportDraftView draft = draft();
        when(drafts.get("draft-1")).thenReturn(draft);
        when(repository.findByDraftIdAndUserId("draft-1", 7L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.listForSubject(eq(identity), eq("WEEKLY_REPORT"), eq("draft-1"))).thenReturn(List.of());

        WeeklyReportExportService.WeeklyReportExportView result = service.prepare("draft-1",
                new WeeklyReportExportService.ExportRequest(true, true, true, "owner@example.com", "本周周报"));

        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.actionPlan().actions()).extracting(action -> action.type())
                .containsExactly("WRITE_DOCUMENT", "WRITE_TABLE", "CREATE_MAIL_DRAFT");
        assertThat(result.mailSubject()).isEqualTo("本周周报");
        verify(actions, never()).approve(any());
        verify(documents, never()).createWordReport(anyString(), anyString(), anyString());
        verify(workspace, never()).write(any(), anyString(), any());
    }

    @Test
    void approveWritesSelectedFilesAndLocalMailDraftOnce() throws Exception {
        RequestIdentityContext.set(identity);
        WeeklyReportDraftService.WeeklyReportDraftView draft = draft();
        ActionPlanViewHolder holder = planFor(draft);
        WeeklyReportExportRecord record = new WeeklyReportExportRecord("export-1", identity, "draft-1",
                mapper.writeValueAsString(holder.plan), mapper.writeValueAsString(draft), true, true, true,
                "owner@example.com", "本周周报");
        when(repository.findByIdAndUserId("export-1", 7L)).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.listForSubject(eq(identity), eq("WEEKLY_REPORT"), eq("draft-1"))).thenReturn(List.of());
        when(actions.claim(any(), anyString())).thenReturn(ActionExecutionService.Claim.EXECUTE);
        when(permissions.current()).thenReturn(new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                List.of(new WorkspaceRoot("C:/workspace", true, true))));
        when(documents.createWordReport(anyString(), anyString(), anyString())).thenReturn("C:/workspace/report.docx");
        when(documents.createExcelStats(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("C:/workspace/report.xlsx");
        when(workspace.write(eq(identity), anyString(), any())).thenReturn(Path.of("C:/workspace/mail.md"));

        WeeklyReportExportService.WeeklyReportExportView result = service.approve("export-1");

        assertThat(result.status()).isEqualTo("APPLIED");
        verify(documents).createWordReport(eq("本周周报"), anyString(), anyString());
        verify(documents).createExcelStats(eq("周报统计"), eq("类型,事项,数值"), anyString(), anyString());
        verify(workspace).write(eq(identity), anyString(), any());
        verify(actions).approve(any());
    }

    @Test
    void workspacePermissionFailureKeepsExportFailedAndDoesNotCallDocumentTool() throws Exception {
        RequestIdentityContext.set(identity);
        WeeklyReportDraftService.WeeklyReportDraftView draft = draft();
        ActionPlanViewHolder holder = planFor(draft);
        WeeklyReportExportRecord record = new WeeklyReportExportRecord("export-1", identity, "draft-1",
                mapper.writeValueAsString(holder.plan), mapper.writeValueAsString(draft), true, false, false, "", "");
        when(repository.findByIdAndUserId("export-1", 7L)).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(artifacts.listForSubject(eq(identity), eq("WEEKLY_REPORT"), eq("draft-1"))).thenReturn(List.of());
        when(actions.claim(any(), anyString())).thenReturn(ActionExecutionService.Claim.EXECUTE);
        when(permissions.current()).thenReturn(new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE,
                List.of(new WorkspaceRoot("C:/workspace", true, false))));

        WeeklyReportExportService.WeeklyReportExportView result = service.approve("export-1");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("写权限");
        verify(documents, never()).createWordReport(anyString(), anyString(), anyString());
        verify(actions).fail(any(), eq("word"), eq("WORD_EXPORT_FAILED"), anyString());
    }

    @Test
    void approvingAppliedExportIsIdempotent() throws Exception {
        RequestIdentityContext.set(identity);
        WeeklyReportDraftService.WeeklyReportDraftView draft = draft();
        ActionPlanViewHolder holder = planFor(draft);
        WeeklyReportExportRecord record = new WeeklyReportExportRecord("export-1", identity, "draft-1",
                mapper.writeValueAsString(holder.plan), mapper.writeValueAsString(draft), true, false, false, "", "");
        record.markApproved();
        record.markApplied();
        when(repository.findByIdAndUserId("export-1", 7L)).thenReturn(Optional.of(record));
        when(artifacts.listForSubject(eq(identity), eq("WEEKLY_REPORT"), eq("draft-1"))).thenReturn(List.of());

        WeeklyReportExportService.WeeklyReportExportView result = service.approve("export-1");

        assertThat(result.status()).isEqualTo("APPLIED");
        verify(actions, never()).approve(any());
        verify(documents, never()).createWordReport(anyString(), anyString(), anyString());
    }

    private ActionPlanViewHolder planFor(WeeklyReportDraftService.WeeklyReportDraftView draft) {
        return new ActionPlanViewHolder(ActionPlanView.weeklyReportExport(draft.id(), draft.title(), true, true, true,
                "owner@example.com", "weekly-report-draft-1.docx", "weekly-report-draft-1.xlsx",
                "weekly-report-draft-1-mail-draft.md", "DRAFT"));
    }

    private static WeeklyReportDraftService.WeeklyReportDraftView draft() {
        WeeklyReportService.WeeklyReportView facts = new WeeklyReportService.WeeklyReportView("weekly:key", "WEEKLY",
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23), List.of(),
                List.of(new WeeklyReportService.CalendarFact(1L, "项目周会", "2026-08-19 10:00",
                        "2026-08-19 11:00", false)),
                List.of(new WeeklyReportService.TodoFact("todo-1", "完成登录", "2026-08-20", true,
                        "2026-08-18 10:00")),
                new WeeklyReportService.Statistics(1, 1, 1, 0, 0), "", List.of(), "2026-08-23T00:00:00Z");
        return new WeeklyReportDraftService.WeeklyReportDraftView("draft-1", "DRAFT", "本周周报", "完成上线",
                "无阻塞", "继续交付", true, true, facts,
                "# 本周周报\n\n## 本周概览\n- 会议数量：1", "指标,数值\n会议数量,1", List.of(),
                "2026-08-23T00:00:00Z", "2026-08-23T00:00:00Z");
    }

    private record ActionPlanViewHolder(com.example.vatica.action.ActionPlanView plan) { }
}
