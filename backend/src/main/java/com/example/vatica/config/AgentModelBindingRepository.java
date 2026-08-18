package com.example.vatica.config;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 迭代 17C：Agent 模型绑定查询。 */
public interface AgentModelBindingRepository extends JpaRepository<AgentModelBinding, String> {

    Optional<AgentModelBinding> findByScopeAndScopeRefAndAgentId(String scope, Long scopeRef, String agentId);

    List<AgentModelBinding> findByScopeAndScopeRefOrderByAgentIdAsc(String scope, Long scopeRef);
}
