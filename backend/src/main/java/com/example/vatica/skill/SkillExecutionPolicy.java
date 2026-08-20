package com.example.vatica.skill;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.vatica.skill.SkillCatalogService.ExecutionProfile;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/** 迭代 20D：在运行时机械执行 Skill 工具交集、能力声明和资源额度。 */
public final class SkillExecutionPolicy {

    private SkillExecutionPolicy() {
    }

    /** 迭代 22B：Skill 资源额度直接作用于 AgentScope 工具，不依赖 Spring AI 回调。 */
    public static AgentTool[] constrain(ExecutionProfile skill, AgentTool[] authorizedTools) {
        if (skill == null) {
            return authorizedTools == null ? new AgentTool[0] : authorizedTools.clone();
        }
        SkillCapabilityPolicy.validate(skill.id() + "@" + skill.version(), skill.tools(), skill.permissions());
        Set<String> declared = Set.copyOf(skill.tools());
        AgentTool[] selected = Arrays.stream(authorizedTools == null ? new AgentTool[0] : authorizedTools)
                .filter(tool -> declared.contains(tool.getName())).toArray(AgentTool[]::new);
        Set<String> available = Arrays.stream(selected).map(AgentTool::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> missing = new LinkedHashSet<>(declared);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("操作失败：Skill " + skill.id() + "@" + skill.version()
                    + " 的授权工具不可用（" + String.join(", ", missing) + "）。");
        }
        AtomicInteger calls = new AtomicInteger();
        return Arrays.stream(selected).map(tool -> guarded(tool, skill, calls)).toArray(AgentTool[]::new);
    }

    private static AgentTool guarded(AgentTool delegate, ExecutionProfile skill, AtomicInteger calls) {
        return new AgentTool() {
            @Override public String getName() { return delegate.getName(); }
            @Override public String getDescription() { return delegate.getDescription(); }
            @Override public java.util.Map<String, Object> getParameters() { return delegate.getParameters(); }
            @Override public Boolean getStrict() { return delegate.getStrict(); }
            @Override public java.util.Map<String, Object> getOutputSchema() { return delegate.getOutputSchema(); }
            @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                if (calls.incrementAndGet() > skill.limits().maxToolCalls()) {
                    return Mono.just(ToolResultBlock.error("操作失败：Skill " + skill.id() + "@" + skill.version()
                            + " 已达到工具调用上限（" + skill.limits().maxToolCalls() + " 次）。"));
                }
                return delegate.callAsync(param);
            }
        };
    }

}
