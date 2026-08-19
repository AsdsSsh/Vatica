package com.example.vatica.skill;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;

/** 迭代 20D：在运行时机械执行 Skill 工具交集、能力声明和资源额度。 */
public final class SkillExecutionPolicy {

    private SkillExecutionPolicy() {
    }

    public static ToolCallback[] constrain(ExecutionProfile skill, ToolCallback[] authorizedCallbacks) {
        if (skill == null) {
            return authorizedCallbacks == null ? new ToolCallback[0] : authorizedCallbacks.clone();
        }
        SkillCapabilityPolicy.validate(skill.id() + "@" + skill.version(), skill.tools(), skill.permissions());
        Set<String> declared = Set.copyOf(skill.tools());
        ToolCallback[] selected = Arrays.stream(authorizedCallbacks == null
                        ? new ToolCallback[0] : authorizedCallbacks)
                .filter(callback -> declared.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
        Set<String> available = Arrays.stream(selected).map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> missing = new LinkedHashSet<>(declared);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("操作失败：Skill " + skill.id() + "@" + skill.version()
                    + " 的授权工具不可用（" + String.join(", ", missing) + "）。");
        }
        AtomicInteger calls = new AtomicInteger();
        return Arrays.stream(selected).map(callback -> guarded(callback, skill, calls))
                .toArray(ToolCallback[]::new);
    }

    private static ToolCallback guarded(ToolCallback delegate, ExecutionProfile skill, AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                int current = calls.incrementAndGet();
                if (current > skill.limits().maxToolCalls()) {
                    throw new IllegalStateException("操作失败：Skill " + skill.id() + "@" + skill.version()
                            + " 已达到工具调用上限（" + skill.limits().maxToolCalls() + " 次）。");
                }
                return delegate.call(toolInput);
            }
        };
    }
}
