package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务计划（Planner 输出结构，迭代 5 I5-1）：步骤列表 + 各步骤结果。
 * 序列化为 JSON 存 TaskRecord.planJson。
 * 迭代 15 I15-11：黑板模型——globalNotes 滚动笔记 + noteThroughStepId 水位线 + 每步 resultDigest。
 * 迭代 17B：增加四原语审计条目、运行中重规划预算与 discovery 补步预算。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    public static final int MAX_BLACKBOARD_ENTRIES = 64;

    private List<TaskStep> steps = List.of();

    /** 跨步骤滚动笔记（旧结果压缩而来，避免每步都读全部前序全文）。 */
    private String globalNotes;

    /** 已并入 globalNotes 的最大步骤 id（水位线）。 */
    private int noteThroughStepId;

    /** result/note/need-help/conflict 条目；与计划一起原子持久化，天然按任务隔离。 */
    private List<BlackboardEntry> blackboard = new ArrayList<>();

    /** 运行中 need-help/conflict 已消耗的 Planner 调整次数，上限 1。 */
    private int collaborationRevisionCount;

    /** Agent discovery 已追加步骤数，上限 2。 */
    private int discoveryStepCount;

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

    public List<BlackboardEntry> getBlackboard() {
        return blackboard;
    }

    public void setBlackboard(List<BlackboardEntry> blackboard) {
        this.blackboard = new ArrayList<>();
        if (blackboard != null) {
            for (BlackboardEntry entry : blackboard) {
                if (!addBlackboardEntry(entry)) {
                    throw new IllegalArgumentException("操作失败：任务黑板超过容量且无法安全淘汰待裁决条目。");
                }
            }
        }
    }

    /**
     * 添加黑板条目。容量达到上限且全部条目都处于 OPEN 时拒绝新增，
     * 不能为了容纳新条目而删除仍待人工/Planner 裁决的旧条目。
     *
     * @return true 表示条目已写入；false 表示没有可安全淘汰的条目
     */
    public boolean addBlackboardEntry(BlackboardEntry entry) {
        if (entry == null) {
            return false;
        }
        if (blackboard == null) {
            blackboard = new ArrayList<>();
        }
        blackboard.add(entry);
        while (blackboard.size() > MAX_BLACKBOARD_ENTRIES) {
            int removable = -1;
            for (int i = 0; i < blackboard.size(); i++) {
                if (!BlackboardEntry.OPEN.equals(blackboard.get(i).status())) {
                    removable = i;
                    break;
                }
            }
            if (removable < 0) {
                // 新条目不能挤掉已有 OPEN 仲裁项；撤销刚刚尝试的追加。
                blackboard.removeLast();
                return false;
            }
            blackboard.remove(removable);
        }
        return true;
    }

    public int getCollaborationRevisionCount() {
        return collaborationRevisionCount;
    }

    public void setCollaborationRevisionCount(int collaborationRevisionCount) {
        this.collaborationRevisionCount = collaborationRevisionCount;
    }

    public int getDiscoveryStepCount() {
        return discoveryStepCount;
    }

    public void setDiscoveryStepCount(int discoveryStepCount) {
        this.discoveryStepCount = discoveryStepCount;
    }

    /** 单个执行步骤。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TaskStep {

        private int id;
        private String description;
        /** 迭代 17A：执行角色 id；旧计划缺失或非法值在执行前回退 general。 */
        private String agent;
        /** 迭代 20B：由 Vatica 固定的受控 Skill；Planner 不直接决定版本。 */
        private String skillId;
        private String skillVersion;
        /** 迭代 23A：Planner 声明且 Vatica 归一化的本步骤所需工具。 */
        private List<String> requiredTools = List.of();
        /** 迭代 23A：能力不匹配时的确定性回退说明，供 HITL 预览与审计。 */
        private String capabilityResolution;
        private boolean needsApproval;
        /** 用户已审批通过（执行流据此跳过审批点；返工需重新审批，迭代 5.5 语义）。 */
        private boolean approved;
        /** 执行结果摘要（执行完回填）。 */
        private String result;
        /** 迭代 15 I15-11：≤200 字的结果要点（供后续依赖步骤注入；无摘要时用 500 字截断兜底）。 */
        private String resultDigest;
        /** 迭代 29C：结果摘要来源，避免把确定性截断误认为模型摘要。 */
        private String resultDigestSource;
        /** 迭代 29C：用户已确认本次降级/待刷新上下文可用于该副作用步骤。 */
        private boolean contextGateApproved;
        /** 迭代 6：依赖的前序步骤编号（1 起）。null=依赖上一步（顺序执行）；空列表=与步骤 1 并行。 */
        private List<Integer> dependsOn;
        /** 迭代 17B：显式共享写资源键，例如 file:C:/work/report.docx；同波重复声明会在执行前冲突检测。 */
        private List<String> writeResources = List.of();

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

        public String getSkillId() {
            return skillId;
        }

        public void setSkillId(String skillId) {
            this.skillId = skillId;
        }

        public String getSkillVersion() {
            return skillVersion;
        }

        public void setSkillVersion(String skillVersion) {
            this.skillVersion = skillVersion;
        }

        public List<String> getRequiredTools() {
            return requiredTools;
        }

        public void setRequiredTools(List<String> requiredTools) {
            this.requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        }

        public String getCapabilityResolution() {
            return capabilityResolution;
        }

        public void setCapabilityResolution(String capabilityResolution) {
            this.capabilityResolution = capabilityResolution;
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

        public String getResultDigestSource() {
            return resultDigestSource;
        }

        public void setResultDigestSource(String resultDigestSource) {
            this.resultDigestSource = resultDigestSource;
        }

        public boolean isContextGateApproved() {
            return contextGateApproved;
        }

        public void setContextGateApproved(boolean contextGateApproved) {
            this.contextGateApproved = contextGateApproved;
        }

        public List<Integer> getDependsOn() {
            return dependsOn;
        }

        public void setDependsOn(List<Integer> dependsOn) {
            this.dependsOn = dependsOn;
        }

        public List<String> getWriteResources() {
            return writeResources;
        }

        public void setWriteResources(List<String> writeResources) {
            this.writeResources = writeResources == null ? List.of() : List.copyOf(writeResources);
        }
    }
}
