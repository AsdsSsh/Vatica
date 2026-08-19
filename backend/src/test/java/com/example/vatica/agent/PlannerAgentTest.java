package com.example.vatica.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.BlackboardEntry;
import com.example.vatica.task.CollaborationDecision;
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
                  {"description":"读取数据文件","agent":"workspace","needsApproval":false},
                  {"description":"发送邮件通知","agent":"pim","needsApproval":true}
                ]}""");

        TaskPlan plan = planner.plan("生成周报并邮件通知");

        assertThat(plan.getSteps()).hasSize(2);
        assertThat(plan.getSteps().get(0).getDescription()).contains("读取数据");
        assertThat(plan.getSteps().get(0).isNeedsApproval()).isFalse();
        assertThat(plan.getSteps().get(0).getAgent()).isEqualTo("workspace");
        assertThat(plan.getSteps().get(1).isNeedsApproval()).isTrue();
        assertThat(plan.getSteps().get(1).getAgent()).isEqualTo("pim");
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

    /** 迭代 15 I15-12：工具清单从 ToolCallbackProvider 动态生成，不再手写。 */
    @Test
    void systemPromptUsesDynamicToolList() {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name("read_file").description("d").inputSchema("{}").build());
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] { callback });
        planner = new PlannerAgent(chatClient, new ObjectMapper(), provider);
        when(callSpec.content()).thenReturn("{\"steps\":[{\"description\":\"读取文件\",\"needsApproval\":false}]}");

        planner.plan("目标");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(spec, org.mockito.Mockito.atLeastOnce()).system(captor.capture());
        assertThat(captor.getValue()).contains("当前可用工具：")
                .contains("read_file")
                .contains("基于知识库生成文档");
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

    /** 迭代 17A：旧计划缺字段、模型输出未知角色均确定性回退 general。 */
    @Test
    void missingOrUnknownAgentFallsBackToGeneral() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"description":"A","needsApproval":false},
                  {"description":"B","agent":"invented-role","needsApproval":false}
                ]}""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps()).extracting(TaskStep::getAgent)
                .containsExactly("general", "general");
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

    /** 迭代 6：显式 [] 声明并行 → 依赖归一化为空列表。 */
    @Test
    void normalizesExplicitParallelDependencies() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"description":"查日历","needsApproval":false,"dependsOn":[]},
                  {"description":"查天气","needsApproval":false,"dependsOn":[]}
                ]}""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps().get(0).getDependsOn()).isEmpty();
        assertThat(plan.getSteps().get(1).getDependsOn()).isEmpty();
    }

    /** 迭代 6：字段缺失 → 顺序执行（依赖上一步）；首步无依赖。 */
    @Test
    void missingDependsOnDefaultsSequential() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"description":"A","needsApproval":false},
                  {"description":"B","needsApproval":false},
                  {"description":"C","needsApproval":false}
                ]}""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps().get(0).getDependsOn()).isEmpty();
        assertThat(plan.getSteps().get(1).getDependsOn()).containsExactly(1);
        assertThat(plan.getSteps().get(2).getDependsOn()).containsExactly(2);
    }

    /** 迭代 6：声明合法依赖 → 保留并随重编号生效；非法引用 → 保守退回顺序执行。 */
    @Test
    void validatesDeclaredDependencies() {
        when(callSpec.content()).thenReturn("""
                {"steps":[
                  {"description":"A","needsApproval":false,"dependsOn":[]},
                  {"description":"B","needsApproval":false,"dependsOn":[1]},
                  {"description":"C","needsApproval":false,"dependsOn":[99,-1]}
                ]}""");

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps().get(1).getDependsOn()).containsExactly(1);
        // 声明的依赖全部非法 → 不授予并行，退回依赖上一步
        assertThat(plan.getSteps().get(2).getDependsOn()).containsExactly(2);
    }

    /** 17B：协作 Planner 的 JSON 裁决可改派/串行化，无法结构化时仍走文本解析。 */
    @Test
    void parsesCollaborationDecision() {
        when(callSpec.content()).thenReturn("""
                {"resolved":true,"summary":"先读后写","patches":[
                  {"stepId":2,"agent":"workspace","dependsOn":[1],"writeResources":["FILE:C:\\\\Work\\\\A.txt"]}
                ],"discoveries":[]}
                """);
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(new TaskStep(1, "读取", false), new TaskStep(2, "写入", false)));
        BlackboardEntry conflict = new BlackboardEntry(null, BlackboardEntry.CONFLICT, 1,
                "planner", "SYSTEM", "同路径写冲突", "file:c:/work/a.txt", List.of(1, 2),
                BlackboardEntry.OPEN, null);

        CollaborationDecision decision = planner.resolveCollaboration(
                "整理文件", plan, List.of(conflict), 2);

        assertThat(decision.resolved()).isTrue();
        assertThat(decision.patches()).singleElement().satisfies(patch -> {
            assertThat(patch.stepId()).isEqualTo(2);
            assertThat(patch.agent()).isEqualTo("workspace");
            assertThat(patch.dependsOn()).containsExactly(1);
        });
    }

    /** 17B：初始计划中的共享资源键在进入调度前做大小写和路径分隔符归一化。 */
    @Test
    void normalizesWriteResourceKeys() {
        when(callSpec.content()).thenReturn("""
                {"steps":[{"description":"写报告","agent":"document","needsApproval":false,
                 "dependsOn":[],"writeResources":["FILE:C:\\\\Work\\\\Report.docx"]}]}
                """);

        TaskPlan plan = planner.plan("目标");

        assertThat(plan.getSteps().getFirst().getWriteResources())
                .containsExactly("file:c:/work/report.docx");
    }
}
