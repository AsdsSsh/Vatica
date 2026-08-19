package com.example.vatica.skill;

import java.util.Collection;

/** 迭代 20D：由不可变工具声明确定性推导的 Skill 运行资源上限。 */
public record SkillResourceLimits(int maxIterations, int maxToolCalls, int maxOutputChars) {

    public static final int ABSOLUTE_MAX_ITERATIONS = 8;
    public static final int ABSOLUTE_MAX_TOOL_CALLS = 8;
    public static final int ABSOLUTE_MAX_OUTPUT_CHARS = 6_000;

    public SkillResourceLimits {
        if (maxIterations < 1 || maxIterations > ABSOLUTE_MAX_ITERATIONS) {
            throw new IllegalArgumentException("Skill maxIterations 必须在 1-8 之间。");
        }
        if (maxToolCalls < 1 || maxToolCalls > ABSOLUTE_MAX_TOOL_CALLS) {
            throw new IllegalArgumentException("Skill maxToolCalls 必须在 1-8 之间。");
        }
        if (maxOutputChars < 512 || maxOutputChars > ABSOLUTE_MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException("Skill maxOutputChars 必须在 512-6000 之间。");
        }
    }

    /** 工具列表属于版本指纹，因此推导结果也随固定版本保持稳定。 */
    public static SkillResourceLimits forTools(Collection<String> tools) {
        int size = tools == null ? 0 : tools.size();
        int calls = Math.min(ABSOLUTE_MAX_TOOL_CALLS, Math.max(2, size + 1));
        int iterations = Math.min(ABSOLUTE_MAX_ITERATIONS, calls + 1);
        return new SkillResourceLimits(iterations, calls, ABSOLUTE_MAX_OUTPUT_CHARS);
    }
}
