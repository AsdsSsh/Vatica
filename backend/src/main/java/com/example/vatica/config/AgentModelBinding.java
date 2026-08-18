package com.example.vatica.config;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 迭代 17C：Agent 模型绑定。绑定只引用平台模型槽位 id，不保存任何凭据。
 * scopeRef 在 USER/ORG 下分别是用户 id/组织 id，PLATFORM 使用 0。
 */
@Entity
@Table(name = "agent_model_binding", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_model_binding_scope_agent", columnNames = { "scope", "scopeRef", "agentId" }), indexes = {
                @Index(name = "idx_agent_model_binding_agent", columnList = "agentId") })
public class AgentModelBinding {

    public static final String SCOPE_USER = "USER";
    public static final String SCOPE_ORG = "ORG";
    public static final String SCOPE_PLATFORM = "PLATFORM";

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 16)
    private String scope;

    @Column(nullable = false)
    private Long scopeRef;

    @Column(nullable = false, length = 32)
    private String agentId;

    @Column(nullable = false, length = 64)
    private String slotId;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AgentModelBinding() {
        // JPA
    }

    public AgentModelBinding(String id, String scope, Long scopeRef, String agentId, String slotId,
            int priority, boolean enabled) {
        this.id = id;
        this.scope = scope;
        this.scopeRef = scopeRef;
        this.agentId = agentId;
        this.slotId = slotId;
        this.priority = priority;
        this.enabled = enabled;
    }

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getScope() { return scope; }
    public Long getScopeRef() { return scopeRef; }
    public String getAgentId() { return agentId; }
    public String getSlotId() { return slotId; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
