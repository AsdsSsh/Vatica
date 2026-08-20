package com.example.vatica.tool;

import io.agentscope.core.tool.AgentTool;

/** 迭代 22B：Vatica 对 AgentScope 工具目录的最小契约。 */
@FunctionalInterface
public interface AgentToolProvider {

    AgentTool[] getAgentTools();
}
