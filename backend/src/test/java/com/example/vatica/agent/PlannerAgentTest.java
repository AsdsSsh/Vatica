package com.example.vatica.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;

import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Planner 单测（迭代 5 I5-1）：JSON 解析（含 markdown 围栏剥除）、审批标记映射、
 * 降级策略（非法 JSON → 单步计划）、归一化（重编号/截断）。
 */
class PlannerAgentTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private PlannerAgent planner;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        planner = new PlannerAgent(chatClient, new ObjectMapper());
    }

    /** 正常 JSON → 解析出步骤与审批标记 */
    @Test
    void parsesValidJsonPlan() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"description":"读取数据文件","needsApproval":false},
                  {"description":"发送邮件通知","needsApproval":true}
                ]}""");

        TaskPlan plan = planner.plan("生成周报并邮件通知");

        assertThat(plan.getSteps()).hasSize(2);
        assertThat(plan.getSteps().get(0).getDescription()).contains("读取数据");
        assertThat(plan.getSteps().get(0).isNeedsApproval()).isFalse();
        assertThat(plan.getSteps().get(1).isNeedsApproval()).isTrue();
    }

    /** markdown 代码围栏包裹的 JSON → 剥除后正常解析 */
    @Test
    void stripsMarkdownFences() {
        when(callSpec.content()).thenReturn("""
                好的，计划如下：
                ```json
                {"steps":[{"description":"步骤A","needsApproval":false}]}
                ```
                请审阅。""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.getSteps().get(0).getDescription()).isEqualTo("步骤A");
    }

    /** 非法输出 → 降级单步计划（不阻断任务创建） */
    @Test
    void invalidOutputDegradesToSingleStep() {
        when(callSpec.content()).thenReturn("抱歉，我无法规划这个任务。");

        TaskPlan plan = planner.plan("目标X");

        assertThat(plan.getSteps()).hasSize(1);
        assertThat(plan.getSteps().get(0).getDescription()).contains("目标X");
        assertThat(plan.getSteps().get(0).isNeedsApproval()).isFalse();
    }

    /** null 输出 → 同样降级 */
    @Test
    void nullOutputDegrades() {
        when(callSpec.content()).thenReturn(null);

        assertThat(planner.plan("目标Y").getSteps()).hasSize(1);
    }

    /** 归一化：步骤重编号 1..n（不信任模型编号） */
    @Test
    void renumbersStepsSequentially() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"id":99,"description":"A","needsApproval":false},
                  {"id":7,"description":"B","needsApproval":false}
                ]}""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps().get(0).getId()).isEqualTo(1);
        assertThat(plan.getSteps().get(1).getId()).isEqualTo(2);
    }

    /** 超长计划截断到 MAX_STEPS */
    @Test
    void capsPlanLength() throws Exception {
        List<TaskStep> many = new java.util.ArrayList<>();
        for (int i = 0; i < PlannerAgent.MAX_STEPS + 3; i++) {
            many.add(new TaskStep(i, "步骤" + i, false));
        }
        TaskPlan tooLong = new TaskPlan();
        tooLong.setSteps(many);
        when(callSpec.content()).thenReturn(new ObjectMapper().writeValueAsString(tooLong));

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps()).hasSize(PlannerAgent.MAX_STEPS);
    }
}
