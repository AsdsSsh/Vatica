package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 任务编排集成测试（迭代 5 I5-3/I5-7）：真实 H2 持久化 + Mock 两个 Agent（LLM 行为 mock 化）。
 * 覆盖：创建→计划审批→执行→敏感步骤审批点挂起→续批→REVIEW→DONE 全链路、
 * 执行失败→FAILED、非法审批拒绝、状态机在编排层被强制执行。
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:vatica-task;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class TaskServiceTest {

    @MockitoBean
    PlannerAgent plannerAgent;
    @MockitoBean
    ExecutorAgent executorAgent;

    @Autowired
    TaskService taskService;
    @Autowired
    TaskRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        when(plannerAgent.plan("目标")).thenReturn(twoStepPlan());
    }

    /** 全链路：创建 PENDING → 审批计划执行第 1 步 → 第 2 步审批点挂起 → 续批 → DONE，全程落库。 */
    @Test
    void fullFlowWithApprovalPoint() {
        when(executorAgent.executeStep(eq("目标"), any(), anyList())).thenReturn("完成该步骤");

        TaskRecord created = taskService.create("目标");
        assertThat(created.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(created.getCurrentStep()).isZero();

        TaskRecord running = taskService.approve(created.getId());
        assertThat(running.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(running.getPendingStepId()).isEqualTo(2);
        TaskPlan midPlan = parsePlan(running);
        assertThat(midPlan.getSteps().get(0).getResult()).isEqualTo("完成该步骤");
        assertThat(midPlan.getSteps().get(1).getResult()).isNull();

        TaskRecord done = taskService.approve(created.getId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getCurrentStep()).isEqualTo(-1);
        TaskPlan finalPlan = parsePlan(done);
        assertThat(finalPlan.getSteps().get(1).getResult()).isEqualTo("完成该步骤");

        // 持久化验证：从库里重读状态一致
        assertThat(repository.findById(created.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }

    /** 步骤执行抛异常 → FAILED 并记录原因（不允许停在 RUNNING 假装成功）。 */
    @Test
    void executionFailureMarksFailed() {
        when(executorAgent.executeStep(eq("目标"), any(), anyList()))
                .thenThrow(new IllegalStateException("上游 API 超时"));

        TaskRecord created = taskService.create("目标");
        TaskRecord failed = taskService.approve(created.getId());

        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getError()).contains("步骤 1").contains("上游 API 超时");
    }

    /** 终态任务审批 → 拒绝（状态机约束生效）。 */
    @Test
    void approveOnTerminalTaskRejected() {
        when(executorAgent.executeStep(eq("目标"), any(), anyList())).thenReturn("完成");
        TaskRecord created = taskService.create("目标");
        taskService.approve(created.getId());
        taskService.approve(created.getId());

        assertThatThrownBy(() -> taskService.approve(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不需要审批");
    }

    /** 空目标 → 拒绝。 */
    @Test
    void blankGoalRejected() {
        assertThatThrownBy(() -> taskService.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标");
    }

    /** 不存在的任务 → 拒绝。 */
    @Test
    void missingTaskRejected() {
        assertThatThrownBy(() -> taskService.get("不存在"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    /** 最近任务按创建时间倒序。 */
    @Test
    void recentListsNewestFirst() {
        TaskRecord first = taskService.create("目标1");
        TaskRecord second = taskService.create("目标2");

        List<TaskRecord> recent = taskService.recent(20);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getId()).isEqualTo(second.getId());
        assertThat(recent.get(1).getId()).isEqualTo(first.getId());
    }

    private static TaskPlan twoStepPlan() {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(
                new TaskStep(1, "读取数据文件", false),
                new TaskStep(2, "发送邮件通知", true)));
        return plan;
    }

    private TaskPlan parsePlan(TaskRecord record) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(record.getPlanJson(), TaskPlan.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
