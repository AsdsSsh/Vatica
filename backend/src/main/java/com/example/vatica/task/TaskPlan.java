package com.example.vatica.task;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务计划（Planner 输出结构，迭代 5 I5-1）：步骤列表 + 各步骤结果。
 * 序列化为 JSON 存 TaskRecord.planJson。
 * 迭代 15 I15-11：黑板模型——globalNotes 滚动笔记 + noteThroughStepId 水位线 + 每步 resultDigest。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    private List<TaskStep> steps = List.of();

    /** 跨步骤滚动笔记（旧结果压缩而来，避免每步都读全部前序全文）。 */
    private String globalNotes;

    /** 已并入 globalNotes 的最大步骤 id（水位线）。 */
    private int noteThroughStepId;

    public List<TaskStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TaskStep> steps) {
        this.steps = steps;
    }

    public String getGlobalNotes() {
        return globalNotes;
    }

    public void setGlobalNotes(String globalNotes) {
        this.globalNotes = globalNotes;
    }

    public int getNoteThroughStepId() {
        return noteThroughStepId;
    }

    public void setNoteThroughStepId(int noteThroughStepId) {
        this.noteThroughStepId = noteThroughStepId;
    }

    /** 单个执行步骤。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskStep {

        private int id;
        private String description;
        /** 迭代 17A：执行角色 id；旧计划缺失或非法值在执行前回退 general。 */
        private String agent;
        private boolean needsApproval;
        /** 用户已审批通过（执行流据此跳过审批点；返工需重新审批，迭代 5.5 语义）。 */
        private boolean approved;
        /** 执行结果摘要（执行完回填）。 */
        private String result;
        /** 迭代 15 I15-11：≤200 字的结果要点（供后续依赖步骤注入；无摘要时用 500 字截断兜底）。 */
        private String resultDigest;
        /** 迭代 6：依赖的前序步骤编号（1 起）。null=依赖上一步（顺序执行）；空列表=与步骤 1 并行。 */
        private List<Integer> dependsOn;

        public TaskStep() {
        }

        public TaskStep(int id, String description, boolean needsApproval) {
            this.id = id;
            this.description = description;
            this.needsApproval = needsApproval;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getAgent() {
            return agent;
        }

        public void setAgent(String agent) {
            this.agent = agent;
        }

        public boolean isNeedsApproval() {
            return needsApproval;
        }

        public void setNeedsApproval(boolean needsApproval) {
            this.needsApproval = needsApproval;
        }

        public boolean isApproved() {
            return approved;
        }

        public void setApproved(boolean approved) {
            this.approved = approved;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getResultDigest() {
            return resultDigest;
        }

        public void setResultDigest(String resultDigest) {
            this.resultDigest = resultDigest;
        }

        public List<Integer> getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(List<Integer> dependsOn) {
            this.dependsOn = dependsOn;
        }
    }
}
