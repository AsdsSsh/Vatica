package com.example.vatica.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;

import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.task.TaskVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Judge Agent 单测（迭代 5.5 I5.5-1）：规则校验先行（不烧 token）、JSON 解析（围栏剥除/维度兜底/越界收敛）、
 * 阈值判定 PASS/FAIL、解析降级兜底。
 */
class JudgeAgentTest {

    private static final int THRESHOLD = 70;

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private JudgeAgent judge;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        judge = new JudgeAgent(chatClient, new ObjectMapper(), THRESHOLD);
    }

    /** 步骤结果为空 → 规则校验直接 FAIL 0 分，且完全不调 LLM（不烧 token）。 */
    @Test
    void blankStepResultFailsByRuleWithoutCallingLlm() {
        TaskPlan plan = planOf(new TaskStep(1, "生成文档", false), new TaskStep(2, "发送通知", false));
        plan.getSteps().get(0).setResult("已完成");

        JudgeAgent.Evaluation eval = judge.evaluate("目标", plan);

        assertThat(eval.score()).isZero();
        assertThat(eval.verdict()).isEqualTo(TaskVerdict.FAIL);
        assertThat(eval.summary()).contains("步骤 2");
        verifyNoInteractions(chatClient);
    }

    /** 计划无步骤 → 规则校验直接 FAIL。 */
    @Test
    void emptyPlanFailsByRule() {
        JudgeAgent.Evaluation eval = judge.evaluate("目标", new TaskPlan());

        assertThat(eval.score()).isZero();
        assertThat(eval.verdict()).isEqualTo(TaskVerdict.FAIL);
        verifyNoInteractions(chatClient);
    }

    /** 正常 JSON：分数 ≥ 阈值 → PASS，summary 透传。 */
    @Test
    void parsesValidScorePass() {
        when(callSpec.content()).thenReturn(
                "{\"score\":85,\"completeness\":25,\"correctness\":45,\"format\":15,\"summary\":\"数据准确、交付完整\"}");

        JudgeAgent.Evaluation eval = judge.evaluate("目标", completePlan());

        assertThat(eval.score()).isEqualTo(85);
        assertThat(eval.verdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(eval.summary()).isEqualTo("数据准确、交付完整");
    }

    /** 分数低于阈值 → FAIL（阈值判定在代码，不信任模型自报结论）。 */
    @Test
    void scoreBelowThresholdFails() {
        when(callSpec.content()).thenReturn("{\"score\":60,\"summary\":\"内容不完整\"}");

        JudgeAgent.Evaluation eval = judge.evaluate("目标", completePlan());

        assertThat(eval.score()).isEqualTo(60);
        assertThat(eval.verdict()).isEqualTo(TaskVerdict.FAIL);
    }

    /** 分数恰好等于阈值 → PASS（边界 >=）。 */
    @Test
    void scoreAtThresholdPasses() {
        when(callSpec.content()).thenReturn("{\"score\":70,\"summary\":\"刚好达标\"}");

        assertThat(judge.evaluate("目标", completePlan()).verdict()).isEqualTo(TaskVerdict.PASS);
    }

    /** markdown 代码围栏包裹 → 剥除后正常解析。 */
    @Test
    void stripsMarkdownFences() {
        when(callSpec.content()).thenReturn("""
                评语如下：
                ```json
                {"score":88,"summary":"优秀"}
                ```
                """);

        assertThat(judge.evaluate("目标", completePlan()).score()).isEqualTo(88);
    }

    /** score 缺失 → 三维度之和兜底。 */
    @Test
    void missingScoreFallsBackToDimensionSum() {
        when(callSpec.content()).thenReturn("{\"completeness\":20,\"correctness\":40,\"format\":10}");

        JudgeAgent.Evaluation eval = judge.evaluate("目标", completePlan());

        assertThat(eval.score()).isEqualTo(70);
        assertThat(eval.verdict()).isEqualTo(TaskVerdict.PASS);
    }

    /** 越界分数收敛到 0-100。 */
    @Test
    void clampsOutOfRangeScores() {
        when(callSpec.content()).thenReturn(
                "{\"score\":150,\"summary\":\"x\"}",
                "{\"score\":-5,\"summary\":\"x\"}");

        assertThat(judge.evaluate("目标", completePlan()).score()).isEqualTo(100);
        assertThat(judge.evaluate("目标", completePlan()).score()).isZero();
    }

    /** 非法输出 → 解析降级：规则已通过 → PASS 兜底（score=null 如实标注），不阻断交付。 */
    @Test
    void unparseableOutputDegradesToPassWithNullScore() {
        when(callSpec.content()).thenReturn("抱歉，我无法评分。");

        JudgeAgent.Evaluation eval = judge.evaluate("目标", completePlan());

        assertThat(eval.verdict()).isEqualTo(TaskVerdict.PASS);
        assertThat(eval.score()).isNull();
        assertThat(eval.summary()).contains("降级");
    }

    /** null 输出 → 同样降级。 */
    @Test
    void nullOutputDegrades() {
        when(callSpec.content()).thenReturn(null);

        assertThat(judge.evaluate("目标", completePlan()).verdict()).isEqualTo(TaskVerdict.PASS);
    }

    private static TaskPlan completePlan() {
        TaskPlan plan = planOf(new TaskStep(1, "读取数据", false));
        plan.getSteps().get(0).setResult("读取完成：文件共 3 行");
        return plan;
    }

    private static TaskPlan planOf(TaskStep... steps) {
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(steps));
        return plan;
    }
}
