package com.example.vatica.runtime;

import java.util.List;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.permission.FilePermissionPolicy;

/**
 * 迭代 15 I15-17：双运行时边界——LegacyRuntime（Spring AI 现状实现）与
 * AgentScopeRuntime（-Pagentscope profile 加载的对照实现）实现同一 POC 契约。
 * 领域状态机 / JPA / HITL / 多租户与权限规则仍是唯一事实源。
 */
public interface AgentRuntime {

    String name();

    record PovResult(String answer, List<String> toolTraces, long durationMs) {
    }

    /** 单 Agent 工具链 POC：目标 + 身份 + 权限快照 → 最终回答与工具轨迹。 */
    PovResult runSingleAgent(String goal, RequestIdentity identity, FilePermissionPolicy permission);

    /** 迭代 15 I15-20：双 Agent 黑板 POC——document/workspace 两个角色经黑板 note 协作。 */
    PovResult runDualAgentBlackboard(String goal, RequestIdentity identity, FilePermissionPolicy permission);
}
