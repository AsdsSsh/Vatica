package com.example.vatica.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 迭代 17B：任务内唯一通信介质的可审计条目。type 使用稳定的小写协议值。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlackboardEntry(String id, String type, int stepId, String agent, String author,
        String content, String resource, List<Integer> relatedStepIds, String status, String createdAt) {

    public static final String RESULT = "result";
    public static final String NOTE = "note";
    public static final String NEED_HELP = "need-help";
    public static final String CONFLICT = "conflict";

    public static final String RECORDED = "RECORDED";
    public static final String OPEN = "OPEN";
    public static final String PLANNER_RESOLVED = "PLANNER_RESOLVED";
    public static final String HUMAN_RESOLVED = "HUMAN_RESOLVED";
    public static final String BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";

    public BlackboardEntry {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        type = type == null ? NOTE : type;
        agent = agent == null || agent.isBlank() ? "general" : agent;
        author = author == null || author.isBlank() ? "AGENT" : author;
        content = content == null ? "" : content;
        relatedStepIds = relatedStepIds == null ? List.of() : List.copyOf(relatedStepIds);
        status = status == null || status.isBlank() ? RECORDED : status;
        createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
    }

    public static BlackboardEntry agent(String type, TaskPlan.TaskStep step, String content, String status) {
        return new BlackboardEntry(null, type, step.getId(), step.getAgent(), "AGENT",
                content, null, List.of(step.getId()), status, null);
    }

    public BlackboardEntry withStatus(String nextStatus) {
        return new BlackboardEntry(id, type, stepId, agent, author, content, resource,
                relatedStepIds, nextStatus, createdAt);
    }
}
