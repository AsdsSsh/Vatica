package com.example.vatica.evaluation;

import java.util.List;

/** 迭代 18B：Legacy 与 AgentScope 共用的固定任务集条目。 */
public record BenchmarkCase(String id, String title, String goal, String expectedAgent,
        List<String> requiredTools, boolean requiresApproval, boolean hasSideEffect,
        String acceptance) {

    public BenchmarkCase {
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }
}
