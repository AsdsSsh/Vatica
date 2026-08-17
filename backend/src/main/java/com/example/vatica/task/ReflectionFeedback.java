package com.example.vatica.task;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Judge 反馈快照（迭代 15 I15-2）：TaskRecord.lastFeedbackJson 的持久化结构。
 * history 保存此前轮次 summary，使 Executor 能看到随 reworkCount 累积的反思链。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReflectionFeedback(Integer score, String summary, List<Integer> failStepIds,
        List<String> history) {

    public ReflectionFeedback {
        failStepIds = failStepIds == null ? List.of() : List.copyOf(failStepIds);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public ReflectionFeedback(Integer score, String summary, List<Integer> failStepIds) {
        this(score, summary, failStepIds, List.of());
    }
}
