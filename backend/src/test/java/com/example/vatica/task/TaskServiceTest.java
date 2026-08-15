package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 任务编排集成测试（迭代 5 I5-3/I5-7；迭代 5.5 I5.5-2/3）：真实 H2 持久化 + Mock 三个 Agent（LLM 行为 mock 化）。
 * 覆盖：创建→计划审批→执行→敏感步骤审批点挂起→续批→REVIEW→DONE 全链路、
 * 执行失败→FAILED、非法审批拒绝、状态机在编排层被强制执行；
 * 5.5 新增：低分自动返工（限 2 次）→ 交付 / 超限 NEEDS_REVISION、人工返工（DONE/NEEDS_REVISION 两入口）、
 * 返工副作用步骤重新审批（可重入设计）、评测异常→FAILED。
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
    @MockitoBean
    JudgeAgent judgeAgent;

    @Autowired
    TaskService taskService;
    @Autowired
    TaskRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        when(plannerAgent.plan("目标")).thenReturn(twoStepPlan());
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class))).thenReturn("完成该步骤");
        when(judgeAgent.evaluate(anyString(), any())).thenReturn(new JudgeAgent.Evaluation(85, TaskVerdict.PASS, "合格"));
    }

    /** 全链路：创建 PENDING → 审批计划执行第 1 步 → 第 2 步审批点挂起 → 续批 → 评测 PASS → DONE，全程落库。 */
    @Test
    void fullFlowWithApprovalPoint() {
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
        assertThat(done.getScore()).isEqualTo(85);
        assertThat(done.getVerdict()).isEqualTo(TaskVerdict.PASS);

        // 持久化验证：从库里重读状态/评测一致
        TaskRecord reloaded = repository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(reloaded.getVerdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(reloaded.getScore()).isEqualTo(85);
    }

    /** 步骤执行抛异常 → FAILED 并记录原因（不允许停在 RUNNING 假装成功）。 */
    @Test
    void executionFailureMarksFailed() {
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class)))
                .thenThrow(new IllegalStateException("上游 API 超时"));

        TaskRecord created = taskService.create("目标");
        TaskRecord failed = taskService.approve(created.getId());

        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getError()).contains("步骤 1").contains("上游 API 超时");
    }

    /** 5.5：低分 → 自动返工重跑 → 二评 PASS → DONE；reworkCount=1，执行器共被调用 2 轮。 */
    @Test
    void lowScoreAutoReworksThenPasses() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any())).thenReturn(
                new JudgeAgent.Evaluation(40, TaskVerdict.FAIL, "内容不完整"),
                new JudgeAgent.Evaluation(80, TaskVerdict.PASS, "返工后合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getReworkCount()).isEqualTo(1);
        assertThat(done.getScore()).isEqualTo(80);
        assertThat(done.getVerdict()).isEqualTo(TaskVerdict.PASS);
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));
    }

    /** 5.5：连续低分 → 自动返工 2 次仍不合格 → NEEDS_REVISION 交人工（限次防死循环）。 */
    @Test
    void lowScoreTwiceExceedsLimitToNeedsRevision() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any()))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "始终不合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());

        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        assertThat(needsRevision.getReworkCount()).isEqualTo(2);
        assertThat(needsRevision.getError()).contains("上限 2 次").contains("人工返工");
        verify(executorAgent, times(3)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));   // 首轮 + 2 次返工
    }

    /** 5.5：NEEDS_REVISION 人工返工 → 重跑 → 二评 PASS → DONE；自动返工窗口被重置（reworkCount=0）。 */
    @Test
    void manualReworkFromNeedsRevisionResetsWindowAndPasses() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any()))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "始终不合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());
        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        assertThat(needsRevision.getReworkCount()).isEqualTo(2);

        when(judgeAgent.evaluate(anyString(), any()))
                .thenReturn(new JudgeAgent.Evaluation(90, TaskVerdict.PASS, "人工返工后合格"));

        TaskRecord done = taskService.rework(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getReworkCount()).isZero();          // 人工返工重置自动返工窗口
        assertThat(done.getScore()).isEqualTo(90);
        assertThat(done.getVerdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(done.getError()).isNull();
        verify(executorAgent, times(4)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));   // 首轮 + 2 返工 + 人工返工
    }

    /** 5.5：已交付 DONE 任务人工返工 → DONE→RETRY→RUNNING 重跑 → 再评测 PASS → DONE。 */
    @Test
    void manualReworkFromDoneReruns() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());

        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);

        TaskRecord redone = taskService.rework(created.getId());

        assertThat(redone.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(redone.getReworkCount()).isZero();
        assertThat(redone.getVerdict()).isEqualTo(TaskVerdict.PASS);
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));
    }

    /** 5.5 可重入设计：返工清空步骤审批标记——副作用步骤（发邮件）重新挂起审批，由人工确认是否重放副作用。 */
    @Test
    void reworkRequiresReApprovalOfSideEffectSteps() {
        TaskRecord created = taskService.create("目标");
        taskService.approve(created.getId());                 // 第 1 步执行、第 2 步挂起
        TaskRecord done = taskService.approve(created.getId());   // 续批 → 评测 PASS → DONE
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);

        TaskRecord redone = taskService.rework(created.getId());

        assertThat(redone.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(redone.getPendingStepId()).isEqualTo(2);   // 副作用步骤重新要求审批
        TaskPlan plan = parsePlan(redone);
        assertThat(plan.getSteps().get(1).isApproved()).isFalse();
        assertThat(plan.getSteps().get(1).getResult()).isNull();
    }

    /** 5.5：非 DONE/NEEDS_REVISION 状态不允许人工返工（PENDING 与 FAILED 均拒绝）。 */
    @Test
    void reworkOnNonReworkableTaskRejected() {
        TaskRecord created = taskService.create("目标");
        assertThatThrownBy(() -> taskService.rework(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许返工");

        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class)))
                .thenThrow(new IllegalStateException("上游 API 超时"));
        TaskRecord failedTask = taskService.create("目标");
        taskService.approve(failedTask.getId());
        TaskRecord reloaded = repository.findById(failedTask.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThatThrownBy(() -> taskService.rework(failedTask.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许返工");
    }

    /** 5.5：评测阶段异常（如 LLM 不可用）→ REVIEW→FAILED 并记录原因。 */
    @Test
    void judgeExceptionMarksFailed() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any())).thenThrow(new IllegalStateException("评测服务不可用"));

        TaskRecord created = taskService.create("目标");
        TaskRecord failed = taskService.approve(created.getId());

        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getError()).contains("评测异常");
    }

    /** 6：并行波并发执行证明——两步骤互相等待对方进场（门闩）；顺序执行会超时导致 FAILED。 */
    @Test
    void parallelWaveRunsConcurrently() throws InterruptedException {
        when(plannerAgent.plan("目标")).thenReturn(parallelPlan());
        CountDownLatch bothEntered = new CountDownLatch(2);
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class))).thenAnswer(inv -> {
            bothEntered.countDown();
            if (!bothEntered.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并行波未并发执行");
            }
            return "完成";
        });

        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(bothEntered.await(3, TimeUnit.SECONDS)).isTrue();
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));
        TaskPlan plan = parsePlan(done);
        assertThat(plan.getSteps().get(0).getResult()).isEqualTo("完成");
        assertThat(plan.getSteps().get(1).getResult()).isEqualTo("完成");
    }

    /** 6：审批点屏障拆分并行波——先执行步骤 1、挂起审批步骤 2；续批后步骤 2、3 并行执行完。 */
    @Test
    void approvalBarrierInParallelPlanThenRejoinWave() {
        when(plannerAgent.plan("目标")).thenReturn(parallelPlanWithApproval());

        TaskRecord created = taskService.create("目标");
        TaskRecord hung = taskService.approve(created.getId());
        assertThat(hung.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(hung.getPendingStepId()).isEqualTo(2);
        assertThat(parsePlan(hung).getSteps().get(0).getResult()).isEqualTo("完成该步骤");

        TaskRecord done = taskService.approve(created.getId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        TaskPlan plan = parsePlan(done);
        assertThat(plan.getSteps().get(1).getResult()).isEqualTo("完成该步骤");
        assertThat(plan.getSteps().get(2).getResult()).isEqualTo("完成该步骤");
        verify(executorAgent, times(3)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class));
    }

    /** 7 I7-4：PENDING 任务可直接终止 → CANCELLED（终态，不可再审批/返工）。 */
    @Test
    void cancelPendingTask() {
        TaskRecord created = taskService.create("目标");

        TaskRecord cancelled = taskService.cancel(created.getId());

        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.getError()).contains("终止");
        assertThatThrownBy(() -> taskService.approve(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不需要审批");
        assertThatThrownBy(() -> taskService.rework(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许返工");
    }

    /** 7 I7-4：运行中终止——执行线程卡在步骤中时取消，执行线程与库中状态最终都是 CANCELLED（协作式取消）。 */
    @Test
    void cancelRunningTaskStopsExecution() throws Exception {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class))).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "完成";
        });

        TaskRecord created = taskService.create("目标");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskRecord> future = pool.submit(() -> taskService.approve(created.getId()));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            TaskRecord cancelled = taskService.cancel(created.getId());
            assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);

            release.countDown();
            TaskRecord result = future.get(10, TimeUnit.SECONDS);
            assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);   // 执行线程以库中状态为准

            TaskRecord reloaded = repository.findById(created.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(reloaded.getError()).contains("终止");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 迭代 10 I10-6：返工执行期间取消标志置位时，rework 尾部做 approve 同款的
     * 悲观锁当前读刷新——不把内存中的 RUNNING 中间态提交回库。
     * （公开 API 并发路径受 READ_COMMITTED 隔离保护，这里直接置位内部标志锁死防御分支。）
     */
    @Test
    @SuppressWarnings("unchecked")
    void reworkHonorsCancellationGuard() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any()))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "不合格"));
        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());
        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);

        TaskService target = AopTestUtils.getUltimateTargetObject(taskService);
        Map<String, AtomicBoolean> flags = new ConcurrentHashMap<>();
        flags.put(created.getId(), new AtomicBoolean(true));
        ReflectionTestUtils.setField(target, "cancelFlags", flags);
        try {
            TaskRecord result = taskService.rework(created.getId());

            assertThat(result.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
            TaskRecord reloaded = repository.findById(created.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        } finally {
            ReflectionTestUtils.setField(target, "cancelFlags", new ConcurrentHashMap<>());
        }
    }

    /** 7 I7-4：PENDING_APPROVAL 挂起时终止同样合法。 */
    @Test
    void cancelApprovalHungTask() {
        TaskRecord created = taskService.create("目标");
        TaskRecord hung = taskService.approve(created.getId());
        assertThat(hung.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);

        TaskRecord cancelled = taskService.cancel(created.getId());

        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    /** 7 I7-4：终态任务不允许终止。 */
    @Test
    void cancelTerminalTaskRejected() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);

        assertThatThrownBy(() -> taskService.cancel(created.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许终止");
    }

    /** 终态任务审批 → 拒绝（状态机约束生效）。 */
    @Test
    void approveOnTerminalTaskRejected() {
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

    /** 最近任务按创建时间倒序（两次创建间隔 >1ms，避免 createdAt 同刻导致排序不稳定）。 */
    @Test
    void recentListsNewestFirst() throws InterruptedException {
        TaskRecord first = taskService.create("目标1");
        Thread.sleep(5);
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

    private static TaskPlan oneStepPlan() {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(new TaskStep(1, "生成统计报告", false)));
        return plan;
    }

    /** 两个互不依赖的步骤（迭代 6 并行计划）。 */
    private static TaskPlan parallelPlan() {
        TaskPlan plan = new TaskPlan();
        TaskStep s1 = new TaskStep(1, "查询日历", false);
        s1.setDependsOn(List.of());
        TaskStep s2 = new TaskStep(2, "查询天气", false);
        s2.setDependsOn(List.of());
        plan.setSteps(List.of(s1, s2));
        return plan;
    }

    /** 三个同层步骤、中间带审批点（迭代 6 屏障拆分：先 1、挂起 2、续批后 2+3 并行）。 */
    private static TaskPlan parallelPlanWithApproval() {
        TaskPlan plan = new TaskPlan();
        TaskStep s1 = new TaskStep(1, "读取数据", false);
        s1.setDependsOn(List.of());
        TaskStep s2 = new TaskStep(2, "发送邮件通知", true);
        s2.setDependsOn(List.of());
        TaskStep s3 = new TaskStep(3, "生成周报", false);
        s3.setDependsOn(List.of());
        plan.setSteps(List.of(s1, s2, s3));
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
