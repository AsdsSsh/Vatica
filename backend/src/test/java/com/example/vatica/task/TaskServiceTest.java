package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.agent.ExecutorAgent;
import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.config.TaskReliabilityProperties;
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
        "vatica.agent.runtime=legacy",
        "spring.datasource.url=jdbc:h2:mem:vatica-task;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class TaskServiceTest {

    private static final RequestIdentity TEST_IDENTITY =
            new RequestIdentity(1L, 1L, "LOCAL", "test");

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
        RequestIdentityContext.set(TEST_IDENTITY);
        repository.deleteAll();
        when(plannerAgent.plan("目标")).thenReturn(twoStepPlan());
        // 迭代 15 I15-2：默认重规划回退旧计划（非法/未 mock 时不改变任务目标）
        when(plannerAgent.revise(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any())).thenReturn("完成该步骤");
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class))).thenReturn(new JudgeAgent.Evaluation(85, TaskVerdict.PASS, "合格"));
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
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
        assertThat(done.getExecutionAttempt()).isEqualTo(1);
        assertThat(done.getExecutionRuntime()).isEqualTo("legacy");
        assertThat(done.getLastHeartbeatAt()).isNotNull();
        assertThat(done.getExecutionFinishedAt()).isNotNull();

        // 持久化验证：从库里重读状态/评测一致
        TaskRecord reloaded = repository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(reloaded.getVerdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(reloaded.getScore()).isEqualTo(85);
    }

    /** 步骤执行抛异常 → FAILED 并记录原因（不允许停在 RUNNING 假装成功）。 */
    @Test
    void executionFailureMarksFailed() {
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any()))
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
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class))).thenReturn(
                new JudgeAgent.Evaluation(40, TaskVerdict.FAIL, "内容不完整"),
                new JudgeAgent.Evaluation(80, TaskVerdict.PASS, "返工后合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getReworkCount()).isEqualTo(1);
        assertThat(done.getScore()).isEqualTo(80);
        assertThat(done.getVerdict()).isEqualTo(TaskVerdict.PASS);
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());
    }

    /** 5.5：连续低分 → 自动返工 2 次仍不合格 → NEEDS_REVISION 交人工（限次防死循环）。 */
    @Test
    void lowScoreTwiceExceedsLimitToNeedsRevision() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class)))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "始终不合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());

        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        assertThat(needsRevision.getReworkCount()).isEqualTo(2);
        assertThat(needsRevision.getError()).contains("上限 2 次").contains("人工返工");
        verify(executorAgent, times(3)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());   // 首轮 + 2 次返工
    }

    /** 5.5：NEEDS_REVISION 人工返工 → 重跑 → 二评 PASS → DONE；自动返工窗口被重置（reworkCount=0）。 */
    @Test
    void manualReworkFromNeedsRevisionResetsWindowAndPasses() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class)))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "始终不合格"));

        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());
        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        assertThat(needsRevision.getReworkCount()).isEqualTo(2);

        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class)))
                .thenReturn(new JudgeAgent.Evaluation(90, TaskVerdict.PASS, "人工返工后合格"));

        TaskRecord done = taskService.rework(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getReworkCount()).isZero();          // 人工返工重置自动返工窗口
        assertThat(done.getScore()).isEqualTo(90);
        assertThat(done.getVerdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(done.getError()).isNull();
        verify(executorAgent, times(4)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());   // 首轮 + 2 返工 + 人工返工
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
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());
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

        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any()))
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
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class))).thenThrow(new IllegalStateException("评测服务不可用"));

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
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any())).thenAnswer(inv -> {
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
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());
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
        verify(executorAgent, times(3)).executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any());
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
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "完成";
        });

        TaskRecord created = taskService.create("目标");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TaskRecord> future = pool.submit(() -> RequestIdentityContext.callWith(
                    TEST_IDENTITY, () -> taskService.approve(created.getId())));
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
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class)))
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

    /** 迭代 14：知道任务 id 也不能跨用户读取、审批或在列表中发现。 */
    @Test
    void taskOwnershipIsEnforcedAcrossUsers() {
        TaskRecord owned = taskService.create("目标");

        RequestIdentityContext.set(new RequestIdentity(2L, 1L, "MEMBER", "other"));
        assertThatThrownBy(() -> taskService.get(owned.getId()))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> taskService.approve(owned.getId()))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(taskService.recent(20)).isEmpty();

        RequestIdentityContext.set(TEST_IDENTITY);
        assertThat(taskService.get(owned.getId()).getUserId()).isEqualTo(1L);
    }

    /** 迭代 15 I15-2：FAIL 后持久化反馈、第 1 次返工触发 Planner 重规划 1 次，二评 PASS 交付。 */
    @Test
    void lowScorePersistsFeedbackAndRevisesPlanOnceThenPasses() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class))).thenReturn(
                new JudgeAgent.Evaluation(40, TaskVerdict.FAIL, "内容不完整", List.of(1)),
                new JudgeAgent.Evaluation(80, TaskVerdict.PASS, "返工后合格"));
        TaskPlan revised = oneStepPlan();
        revised.getSteps().get(0).setDescription("重新生成统计报告并补充数字来源");
        when(plannerAgent.revise(anyString(), any(), any())).thenReturn(revised);

        TaskRecord created = taskService.create("目标");
        TaskRecord done = taskService.approve(created.getId());

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getPlanRevisionCount()).isEqualTo(1);
        assertThat(done.getLastFeedbackJson()).contains("内容不完整").contains("failStepIds");
        assertThat(parsePlan(done).getSteps().get(0).getDescription()).contains("补充数字来源");
        verify(plannerAgent, times(1)).revise(anyString(), any(), any());
    }

    /** 迭代 15 I15-2：连续两轮 FAIL——重规划只允许 1 次，第 2 次返工按旧计划重跑。 */
    @Test
    void secondAutoReworkDoesNotReviseAgain() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class)))
                .thenReturn(new JudgeAgent.Evaluation(30, TaskVerdict.FAIL, "始终不合格", List.of(1)));

        TaskRecord created = taskService.create("目标");
        TaskRecord needsRevision = taskService.approve(created.getId());

        assertThat(needsRevision.getStatus()).isEqualTo(TaskStatus.NEEDS_REVISION);
        assertThat(needsRevision.getPlanRevisionCount()).isEqualTo(1);
        assertThat(needsRevision.getLastFeedbackJson()).contains("始终不合格");
        verify(plannerAgent, times(1)).revise(anyString(), any(), any());
    }

    /** 迭代 15 I15-2：Executor 收到的反馈 prompt 包含 Judge summary 原文。 */
    @Test
    void executorReceivesJudgeSummaryOnAutoRework() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(judgeAgent.evaluate(anyString(), any(), any(ChatClient.class))).thenReturn(
                new JudgeAgent.Evaluation(40, TaskVerdict.FAIL, "关键数字缺失", List.of(1)),
                new JudgeAgent.Evaluation(85, TaskVerdict.PASS, "修复完成"));

        TaskRecord created = taskService.create("目标");
        taskService.approve(created.getId());

        org.mockito.ArgumentCaptor<String> feedbackCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(),
                any(ToolCallback[].class), any(ChatClient.class), feedbackCaptor.capture());
        assertThat(feedbackCaptor.getAllValues()).anyMatch(v -> v != null && v.contains("关键数字缺失"));
    }

    /** 17B：need-help 只消耗一次运行中重规划，Planner 改派后原步骤重跑且已完成步骤不重复。 */
    @Test
    void needHelpTriggersOneCollaborationRevisionAndRerunsStep() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class),
                any(ChatClient.class), any())).thenReturn(
                        "{\"result\":\"缺少目标路径\",\"needHelp\":\"请改派 workspace 确认路径\"}",
                        "路径已确认，处理完成");
        when(plannerAgent.resolveCollaboration(anyString(), any(), anyList(), anyInt(), any(ChatClient.class)))
                .thenReturn(new CollaborationDecision(true, "改派 workspace",
                        List.of(new CollaborationDecision.StepPatch(1, "确认路径后完成处理", "workspace",
                                false, List.of(), List.of())), List.of()));

        TaskRecord done = taskService.approve(taskService.create("目标").getId());
        TaskPlan plan = parsePlan(done);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(plan.getCollaborationRevisionCount()).isEqualTo(1);
        assertThat(plan.getSteps().getFirst().getAgent()).isEqualTo("workspace");
        assertThat(plan.getSteps().getFirst().getResult()).contains("处理完成");
        assertThat(plan.getBlackboard()).extracting(BlackboardEntry::type)
                .contains(BlackboardEntry.NEED_HELP, BlackboardEntry.NOTE, BlackboardEntry.RESULT);
        verify(plannerAgent, times(1)).resolveCollaboration(
                anyString(), any(), anyList(), anyInt(), any(ChatClient.class));
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(),
                any(ToolCallback[].class), any(ChatClient.class), any());
    }

    /** 17B：Planner 可把同路径并发写改为串行，冲突解决前 Worker 不会开始执行。 */
    @Test
    void plannerSerializesSharedResourceConflictBeforeExecution() {
        when(plannerAgent.plan("目标")).thenReturn(conflictingWritePlan());
        when(plannerAgent.resolveCollaboration(anyString(), any(), anyList(), anyInt(), any(ChatClient.class)))
                .thenReturn(new CollaborationDecision(true, "按步骤编号串行写入",
                        List.of(new CollaborationDecision.StepPatch(2, null, null, false,
                                List.of(1), List.of("file:report.docx"))), List.of()));

        TaskRecord done = taskService.approve(taskService.create("目标").getId());
        TaskPlan plan = parsePlan(done);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(plan.getSteps().get(1).getDependsOn()).containsExactly(1);
        assertThat(plan.getBlackboard()).filteredOn(entry -> BlackboardEntry.CONFLICT.equals(entry.type()))
                .extracting(BlackboardEntry::status).containsExactly(BlackboardEntry.PLANNER_RESOLVED);
        verify(executorAgent, times(2)).executeStep(eq("目标"), any(), anyList(),
                any(ToolCallback[].class), any(ChatClient.class), any());
    }

    /** 17B：Planner 未决时进入 HITL；没有人工 note 不能继续，写 note 后按安全默认串行化。 */
    @Test
    void unresolvedConflictRequiresHumanNoteBeforeContinuing() {
        when(plannerAgent.plan("目标")).thenReturn(conflictingWritePlan());
        when(plannerAgent.resolveCollaboration(anyString(), any(), anyList(), anyInt(), any(ChatClient.class)))
                .thenReturn(CollaborationDecision.unresolved("两个写入都可能覆盖用户内容"));

        TaskRecord pending = taskService.approve(taskService.create("目标").getId());

        assertThat(pending.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(pending.getPendingStepId()).isEqualTo(-1);
        assertThatThrownBy(() -> taskService.approve(pending.getId()))
                .hasMessageContaining("人工判断说明");

        taskService.addHumanNote(pending.getId(), "先执行步骤 1，再由步骤 2 校验并覆盖。");
        TaskRecord done = taskService.approve(pending.getId());
        TaskPlan plan = parsePlan(done);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(plan.getSteps().get(1).getDependsOn()).containsExactly(1);
        assertThat(plan.getBlackboard()).anyMatch(entry -> entry.author().startsWith("HUMAN"));
        assertThat(plan.getBlackboard()).filteredOn(entry -> BlackboardEntry.CONFLICT.equals(entry.type()))
                .extracting(BlackboardEntry::status).containsExactly(BlackboardEntry.HUMAN_RESOLVED);
    }

    /** 17B：discovery 补出的副作用步骤仍命中原审批屏障。 */
    @Test
    void discoverySideEffectStepStillRequiresApproval() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class),
                any(ChatClient.class), any())).thenReturn("""
                {"result":"分析完成","discoveries":[{"description":"调用 mail_send 发送分析结果",
                 "agent":"pim","needsApproval":false,"dependsOn":[1]}]}
                """);

        TaskRecord pending = taskService.approve(taskService.create("目标").getId());
        TaskPlan plan = parsePlan(pending);

        assertThat(pending.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(pending.getPendingStepId()).isEqualTo(2);
        assertThat(plan.getDiscoveryStepCount()).isEqualTo(1);
        assertThat(plan.getSteps().get(1).isNeedsApproval()).isTrue();
    }

    /** 17B：第二次 need-help 不再调用 Planner，按预算耗尽结果交给 Judge 收口。 */
    @Test
    void secondNeedHelpExhaustsBudgetWithoutAnotherReplan() {
        when(plannerAgent.plan("目标")).thenReturn(oneStepPlan());
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class),
                any(ChatClient.class), any())).thenReturn(
                        "{\"needHelp\":\"第一次求助\"}",
                        "{\"needHelp\":\"第二次求助\"}");
        when(plannerAgent.resolveCollaboration(anyString(), any(), anyList(), anyInt(), any(ChatClient.class)))
                .thenReturn(new CollaborationDecision(true, "补充步骤说明",
                        List.of(new CollaborationDecision.StepPatch(1, "按补充说明重试", "general",
                                false, List.of(), List.of())), List.of()));

        TaskRecord done = taskService.approve(taskService.create("目标").getId());
        TaskPlan plan = parsePlan(done);

        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(plan.getSteps().getFirst().getResult()).contains("未解决求助");
        assertThat(plan.getBlackboard()).filteredOn(entry -> BlackboardEntry.NEED_HELP.equals(entry.type()))
                .extracting(BlackboardEntry::status)
                .contains(BlackboardEntry.PLANNER_RESOLVED, BlackboardEntry.BUDGET_EXHAUSTED);
        verify(plannerAgent, times(1)).resolveCollaboration(
                anyString(), any(), anyList(), anyInt(), any(ChatClient.class));
    }

    /** 18：同一用户重复提交相同幂等键只规划一次，并返回同一任务。 */
    @Test
    void duplicateIdempotencyKeyReturnsOriginalTaskWithoutReplanning() {
        TaskRecord first = taskService.create("目标", null, null, null, "create-001");
        TaskRecord repeated = taskService.create("目标", null, null, null, "create-001");

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repository.count()).isOne();
        verify(plannerAgent, times(1)).plan("目标");
    }

    /** 18：幂等键不能被偷偷复用于不同请求体，避免客户端拿到不相干的旧任务。 */
    @Test
    void reusedIdempotencyKeyWithDifferentGoalIsRejected() {
        taskService.create("目标", null, null, null, "create-002");

        assertThatThrownBy(() -> taskService.create("另一个目标", null, null, null, "create-002"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幂等键已用于其他任务目标");
    }

    /** 18：重启恢复保留完成步骤，并在中断步骤前强制人工确认，确认后只执行剩余步骤。 */
    @Test
    void resumeKeepsCheckpointAndRequiresApprovalBeforeInterruptedStep() throws Exception {
        TaskPlan plan = twoStepPlan();
        plan.getSteps().getFirst().setResult("已完成且不应重跑");
        TaskRecord interrupted = new TaskRecord("recover-1", 1L, 1L, "目标", TaskStatus.FAILED,
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan), 1, null);
        interrupted.setRecoverable(true);
        repository.save(interrupted);

        TaskRecord pending = taskService.resume(interrupted.getId());

        assertThat(pending.getStatus()).isEqualTo(TaskStatus.PENDING_APPROVAL);
        assertThat(pending.getPendingStepId()).isEqualTo(2);
        assertThat(pending.isRecoveryApprovalRequired()).isTrue();
        assertThat(parsePlan(pending).getSteps().getFirst().getResult()).isEqualTo("已完成且不应重跑");
        assertThat(pending.getExecutionAttempt()).isEqualTo(1);

        TaskRecord done = taskService.approve(interrupted.getId());
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(parsePlan(done).getSteps().getFirst().getResult()).isEqualTo("已完成且不应重跑");
        verify(executorAgent, times(1)).executeStep(eq("目标"), any(), anyList(),
                any(ToolCallback[].class), any(ChatClient.class), any());
    }

    /** 18：步骤超过可靠性边界后进入 FAILED，不能把超时当成空结果继续评测。 */
    @Test
    void stepTimeoutMarksTaskFailed() throws Exception {
        TaskService target = AopTestUtils.getUltimateTargetObject(taskService);
        ReflectionTestUtils.setField(target, "reliability",
                new TaskReliabilityProperties(Duration.ofMillis(30)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executorAgent.executeStep(eq("目标"), any(), anyList(), any(ToolCallback[].class), any(ChatClient.class), any()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return "迟到的结果不应写入任务";
                });

        try {
            TaskRecord failed = taskService.approve(taskService.create("目标").getId());
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(failed.getError()).contains("超时");
            assertThat(parsePlan(failed).getSteps().getFirst().getResult()).isNull();
        } finally {
            release.countDown();
        }
    }

    private static TaskPlan twoStepPlan() {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(
                new TaskStep(1, "读取数据文件", false),
                new TaskStep(2, "发送邮件通知", true)));
        return plan;
    }

    private static TaskPlan conflictingWritePlan() {
        TaskPlan plan = new TaskPlan();
        TaskStep first = new TaskStep(1, "生成报告", false);
        first.setAgent("document");
        first.setDependsOn(List.of());
        first.setWriteResources(List.of("file:report.docx"));
        TaskStep second = new TaskStep(2, "校验并修订报告", false);
        second.setAgent("document");
        second.setDependsOn(List.of());
        second.setWriteResources(List.of("file:report.docx"));
        plan.setSteps(List.of(first, second));
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
