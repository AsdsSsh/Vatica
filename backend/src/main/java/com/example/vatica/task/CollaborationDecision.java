package com.example.vatica.task;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 迭代 17B：Planner 对 need-help/conflict 的受限裁决，只允许修补未完成步骤和提出补步。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollaborationDecision(boolean resolved, String summary, List<StepPatch> patches,
        List<TaskPlan.TaskStep> discoveries) {

    public CollaborationDecision {
        patches = patches == null ? List.of() : List.copyOf(patches);
        discoveries = discoveries == null ? List.of() : List.copyOf(discoveries);
    }

    public static CollaborationDecision unresolved(String summary) {
        return new CollaborationDecision(false, summary, List.of(), List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepPatch(int stepId, String description, String agent, Boolean needsApproval,
            List<Integer> dependsOn, List<String> writeResources) {
    }
}
