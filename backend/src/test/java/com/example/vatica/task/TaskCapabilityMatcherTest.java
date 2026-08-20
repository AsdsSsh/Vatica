package com.example.vatica.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import com.example.vatica.skill.SkillResourceLimits;
import com.example.vatica.task.TaskPlan.TaskStep;

/** 迭代 23A：计划必须在执行前消除角色与 Skill 工具交集为空的机械失败。 */
class TaskCapabilityMatcherTest {

    private final AgentRegistry agents = new AgentRegistry();
    private final ExecutionProfile knowledgeResearch = new ExecutionProfile("knowledge-research", "1.1.0",
            "知识研究", "research", List.of("search_knowledge_base"),
            List.of("knowledge:read", "citation:read"), "检索知识库",
            SkillResourceLimits.forTools(List.of("search_knowledge_base")));

    @Test
    void fallsBackToGeneralBeforeApprovalWhenResearchSkillCannotUseCalculator() {
        TaskStep step = new TaskStep(1, "使用 calculator 计算 12 + 8", false);
        step.setAgent("research");

        TaskCapabilityMatcher.Resolution resolution = TaskCapabilityMatcher.resolve(step, agents, knowledgeResearch,
                Set.of("calculator", "search_knowledge_base"));

        assertThat(resolution.agentId()).isEqualTo(AgentRegistry.GENERAL);
        assertThat(resolution.skill()).isNull();
        assertThat(resolution.requiredTools()).containsExactly("calculator");
        assertThat(resolution.explanation()).contains("knowledge-research").contains("通用 Agent");
    }

    @Test
    void keepsResearchSkillWhenRequiredKnowledgeToolIsCovered() {
        TaskStep step = new TaskStep(1, "调用 search_knowledge_base 检索知识库资料", false);
        step.setAgent("research");
        step.setRequiredTools(List.of("search_knowledge_base"));

        TaskCapabilityMatcher.Resolution resolution = TaskCapabilityMatcher.resolve(step, agents, knowledgeResearch,
                Set.of("calculator", "search_knowledge_base"));

        assertThat(resolution.agentId()).isEqualTo("research");
        assertThat(resolution.skill()).isEqualTo(knowledgeResearch);
        assertThat(resolution.explanation()).isNull();
    }

    @Test
    void rejectsUnavailableDeclaredToolBeforeWorkerExecution() {
        TaskStep step = new TaskStep(1, "调用不存在的工具", false);
        step.setAgent("general");
        step.setRequiredTools(List.of("imaginary_tool"));

        assertThatThrownBy(() -> TaskCapabilityMatcher.resolve(step, agents, null, Set.of("calculator")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前不可用").hasMessageContaining("imaginary_tool");
    }

    @Test
    void executionGuardRejectsLegacyPinnedSkillThatDoesNotCoverRequirement() {
        TaskStep step = new TaskStep(1, "计算总数", false);
        step.setAgent("research");
        step.setRequiredTools(List.of("calculator"));

        assertThatThrownBy(() -> TaskCapabilityMatcher.assertExecutionCompatible(step, agents, knowledgeResearch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("knowledge-research").hasMessageContaining("calculator");
    }
}
