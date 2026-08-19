package com.example.vatica.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;

/** 迭代 20D：工具交集、调用次数和输出大小均在 Vatica 回调层执行。 */
class SkillExecutionPolicyTest {

    @Test
    void exposesOnlyDeclaredTools() {
        ExecutionProfile skill = profile();
        ToolCallback[] constrained = SkillExecutionPolicy.constrain(skill,
                new ToolCallback[] { callback("search_knowledge_base", "x".repeat(7_000)),
                        callback("calculator", "42") });

        assertThat(constrained).singleElement()
                .satisfies(value -> assertThat(value.getToolDefinition().name())
                        .isEqualTo("search_knowledge_base"));
        assertThat(constrained[0].call("{}")).hasSize(7_000);
    }

    @Test
    void failsClosedAfterVersionBoundToolBudget() {
        ExecutionProfile skill = profile();
        ToolCallback callback = SkillExecutionPolicy.constrain(skill,
                new ToolCallback[] { callback("search_knowledge_base", "ok") })[0];

        assertThat(callback.call("{}")).isEqualTo("ok");
        assertThat(callback.call("{}")).isEqualTo("ok");
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("工具调用上限（2 次）");
    }

    private static ExecutionProfile profile() {
        return new ExecutionProfile("knowledge-research", "1.1.0", "知识研究", "research",
                List.of("search_knowledge_base"), List.of("knowledge:read", "citation:read"), "保留引用");
    }

    private static ToolCallback callback(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{}").build());
        when(callback.call(any())).thenReturn(result);
        return callback;
    }
}
