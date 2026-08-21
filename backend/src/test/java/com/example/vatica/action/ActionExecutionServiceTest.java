package com.example.vatica.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 25B：副作用动作持久化状态的幂等、恢复和取消约束。 */
class ActionExecutionServiceTest {

    private final ActionExecutionRecordRepository repository = org.mockito.Mockito.mock(ActionExecutionRecordRepository.class);
    private final ActionExecutionService service = new ActionExecutionService(repository);
    private final RequestIdentity identity = new RequestIdentity(7L, 3L, "USER", "alice");
    private final ActionPlanView plan = plan();

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void approvedActionCanSucceedOnlyOnceAndRepeatedClaimSkipsSideEffect() {
        RequestIdentityContext.set(identity);
        ActionExecutionRecord record = record();
        when(repository.findForUpdate(7L, "meeting-preparation:p1:document"))
                .thenReturn(Optional.of(record));

        assertThat(service.claim(plan, "document")).isEqualTo(ActionExecutionService.Claim.EXECUTE);
        service.succeed(plan, "document", "meeting-preparation-p1.md");
        assertThat(service.claim(plan, "document")).isEqualTo(ActionExecutionService.Claim.ALREADY_SUCCEEDED);
        assertThat(record.getStatus()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
        assertThat(record.getAttemptCount()).isEqualTo(1);
        assertThat(record.getResult()).isEqualTo("meeting-preparation-p1.md");
    }

    @Test
    void failedActionCanBeRequeuedAndCancelledWithoutTouchingSucceededAction() {
        RequestIdentityContext.set(identity);
        ActionExecutionRecord failed = record();
        ActionExecutionRecord succeeded = new ActionExecutionRecord("a2", identity, plan, plan.actions().get(1));
        succeeded.begin();
        succeeded.succeed("todo-1");
        when(repository.findForUpdate(7L, "meeting-preparation:p1:document"))
                .thenReturn(Optional.of(failed));
        when(repository.findForUpdate(7L, "meeting-preparation:p1:todo:1"))
                .thenReturn(Optional.of(succeeded));

        failed.begin();
        failed.fail("WORKSPACE_WRITE_FAILED", "denied");
        service.requeueRecoverable(plan);
        assertThat(failed.getStatus()).isEqualTo(ActionExecutionStatus.APPROVED);
        assertThat(succeeded.getStatus()).isEqualTo(ActionExecutionStatus.SUCCEEDED);

        assertThat(service.cancelNotStarted(plan)).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(ActionExecutionStatus.CANCELLED);
        assertThat(succeeded.getStatus()).isEqualTo(ActionExecutionStatus.SUCCEEDED);
    }

    @Test
    void actionCannotBeExecutedWithoutAnApprovedRecord() {
        RequestIdentityContext.set(identity);
        when(repository.findForUpdate(7L, "meeting-preparation:p1:document")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(plan, "document"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未批准");
        verify(repository, never()).save(any(ActionExecutionRecord.class));
    }

    @Test
    void approveCreatesOneRecordPerActionAndRepeatedApprovalReusesExistingRecords() {
        RequestIdentityContext.set(identity);
        when(repository.findForUpdate(7L, "meeting-preparation:p1:document")).thenReturn(Optional.empty());
        when(repository.findForUpdate(7L, "meeting-preparation:p1:todo:1")).thenReturn(Optional.empty());
        when(repository.save(any(ActionExecutionRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.approve(plan);
        ArgumentCaptor<ActionExecutionRecord> saved = ArgumentCaptor.forClass(ActionExecutionRecord.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(ActionExecutionRecord::getIdempotencyKey)
                .containsExactlyInAnyOrder("meeting-preparation:p1:document", "meeting-preparation:p1:todo:1");
    }

    private ActionExecutionRecord record() {
        return new ActionExecutionRecord("a1", identity, plan, plan.actions().getFirst());
    }

    private static ActionPlanView plan() {
        return new ActionPlanView("meeting-preparation:p1:v1", "MEETING_PREPARATION", "p1", 1, "PREVIEW",
                List.of(
                        new ActionPlanView.ActionItemView("document", "WRITE_DOCUMENT", "写文件", "workspace",
                                "新增文件", "会议 p1", "workspace:write", "MEDIUM",
                                "meeting-preparation:p1:document", "PENDING", "NOT_STARTED", null),
                        new ActionPlanView.ActionItemView("todo-1", "CREATE_TODO", "建待办", "todo",
                                "新增待办", "会议 p1", "pim:todo:write", "MEDIUM",
                                "meeting-preparation:p1:todo:1", "PENDING", "NOT_STARTED", null)));
    }
}
