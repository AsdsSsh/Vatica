package com.example.vatica.task;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务计划（Planner 输出结构，迭代 5 I5-1）：步骤列表 + 各步骤结果。
 * 序列化为 JSON 存 TaskRecord.planJson。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    private List<TaskStep> steps = List.of();

    public List<TaskStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TaskStep> steps) {
        this.steps = steps;
    }

    /** 单个执行步骤。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskStep {

        private int id;
        private String description;
        private boolean needsApproval;
        /** 用户已审批通过（执行流据此跳过审批点；返工需重新审批，迭代 5.5 语义）。 */
        private boolean approved;
        /** 执行结果摘要（执行完回填）。 */
        private String result;
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

        public List<Integer> getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(List<Integer> dependsOn) {
            this.dependsOn = dependsOn;
        }
    }
}
