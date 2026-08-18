package com.example.vatica.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.AdminGuard;
import com.example.vatica.auth.AppUser;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.runtime.AgentRegistry;
import com.example.vatica.runtime.AgentRegistry.AgentDefinition;

/**
 * 迭代 17C：Agent 模型绑定设置与解析链。
 * <p>绑定只允许引用当前平台可见的模型元数据，密钥仍由 ModelConfigService/密文库管理。
 * 明确绑定失效时不直接失败，而是带原因逐级降级，最终交给能力标签和全局默认。
 */
@Service
public class AgentModelBindingService {

    private final AgentModelBindingRepository repository;
    private final ModelConfigService modelConfig;
    private final AgentRegistry agentRegistry;

    public AgentModelBindingService(AgentModelBindingRepository repository, ModelConfigService modelConfig,
            AgentRegistry agentRegistry) {
        this.repository = repository;
        this.modelConfig = modelConfig;
        this.agentRegistry = agentRegistry;
    }

    public record BindingRequest(String scope, String agentId, String slotId) {
    }

    public record BindingView(String scope, Long scopeRef, String agentId, String role, String slotId,
            String slotName, boolean enabled, boolean credentialAvailable, String status) {
    }

    public record SlotOption(String id, String name, String model, boolean enabled, boolean credentialAvailable,
            List<String> capabilities) {
    }

    public record SettingsView(List<BindingView> bindings, List<SlotOption> slots, List<AgentView> agents) {
    }

    public record AgentView(String id, String role, String modelCapability) {
    }

    public record Resolution(ModelSlot slot, String source, boolean fallback, String fallbackReason) {
    }

    @Transactional(readOnly = true)
    public SettingsView settings(RequestIdentity identity) {
        List<BindingView> bindings = new ArrayList<>();
        List<ScopeRef> scopes = visibleScopes(identity);
        for (ScopeRef scope : scopes) {
            repository.findByScopeAndScopeRefOrderByAgentIdAsc(scope.scope(), scope.scopeRef()).stream()
                    .map(this::view).forEach(bindings::add);
        }
        List<SlotOption> slots = modelConfig.slots().stream().map(this::slotOption).toList();
        List<AgentView> agents = agentRegistry.definitions().stream()
                .map(agent -> new AgentView(agent.id(), agent.displayName(), agent.modelCapability())).toList();
        return new SettingsView(List.copyOf(bindings), slots, agents);
    }

    @Transactional
    public BindingView save(RequestIdentity identity, BindingRequest request) {
        String scope = normalizeScope(request == null ? null : request.scope());
        String agentId = agentRegistry.normalizeId(request == null ? null : request.agentId());
        Long scopeRef = checkScope(identity, scope);
        String slotId = trim(request == null ? null : request.slotId());
        if (slotId.isEmpty()) {
            repository.findByScopeAndScopeRefAndAgentId(scope, scopeRef, agentId).ifPresent(repository::delete);
            return new BindingView(scope, scopeRef, agentId, agentRegistry.resolve(agentId).displayName(), null, null,
                    false, false, "FOLLOW_DEFAULT");
        }
        ModelSlot slot = platformSlot(slotId);
        if (!slot.enabled()) {
            throw new IllegalArgumentException("操作失败：不能绑定已禁用的模型槽位（" + slot.name() + "）。");
        }
        if (!agentSlotAvailable(slot)) {
            throw new IllegalArgumentException("操作失败：AgentScope 当前仅支持带可用凭据的 OpenAI 兼容槽位（" + slot.name() + "）。");
        }
        AgentModelBinding binding = repository.findByScopeAndScopeRefAndAgentId(scope, scopeRef, agentId)
                .orElseGet(() -> new AgentModelBinding(UUID.randomUUID().toString(), scope, scopeRef, agentId,
                        slot.id(), scopePriority(scope), true));
        binding = replace(binding, slot.id());
        return view(repository.save(binding));
    }

    /**
     * 解析顺序：USER → ORG → PLATFORM → 能力标签 → 全局默认。
     * 明确绑定的槽位若禁用、没有凭据或不存在，继续降级并保留可解释原因。
     */
    @Transactional(readOnly = true)
    public Resolution resolve(RequestIdentity identity, String requestedAgentId, String capability,
            EphemeralCredential ephemeral) {
        AgentDefinition agent = agentRegistry.resolve(requestedAgentId);
        if (ephemeral != null) {
            return new Resolution(ephemeral.toSlot(), "REQUEST", false, null);
        }
        List<String> reasons = new ArrayList<>();
        for (ScopeRef scope : visibleScopes(identity)) {
            var binding = repository.findByScopeAndScopeRefAndAgentId(scope.scope(), scope.scopeRef(), agent.id())
                    .orElse(null);
            if (binding == null || !binding.isEnabled()) {
                continue;
            }
            try {
                ModelSlot slot = platformSlot(binding.getSlotId());
                if (!slot.enabled()) {
                    reasons.add(scope.scope() + "绑定槽位已禁用");
                    if (!agentSlotAvailable(slot)) {
                        reasons.add(scope.scope() + "绑定槽位缺少凭据");
                    }
                    continue;
                }
                if (!agentSlotAvailable(slot)) {
                    reasons.add(scope.scope() + "绑定槽位缺少凭据");
                    continue;
                }
                return new Resolution(slot, scope.scope(), !reasons.isEmpty(), joinReasons(reasons));
            } catch (IllegalArgumentException e) {
                reasons.add(scope.scope() + "绑定槽位不存在");
            }
        }
        ModelSlot capabilitySlot = modelConfig.slots().stream()
                .filter(slot -> slot.enabled() && slot.capabilities().contains(capability))
                .filter(this::agentSlotAvailable)
                .min(Comparator.comparing(ModelSlot::id))
                .orElse(null);
        if (capabilitySlot != null) {
            return new Resolution(capabilitySlot, "CAPABILITY", !reasons.isEmpty(), joinReasons(reasons));
        }
        ModelSlot global = modelConfig.slots().stream().filter(ModelSlot::enabled)
                .filter(this::agentSlotAvailable).findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：没有可用模型槽位，请检查模型启用状态与凭据。"));
        if (reasons.isEmpty()) {
            return new Resolution(global, "DEFAULT", false, null);
        }
        return new Resolution(global, "DEFAULT", true, joinReasons(reasons));
    }

    /** 迭代 17C：模型 401/超时后的单次角色级恢复，跳过刚失败槽位。 */
    @Transactional(readOnly = true)
    public Resolution recover(RequestIdentity identity, String requestedAgentId, String capability,
            String failedSlotId) {
        AgentDefinition agent = agentRegistry.resolve(requestedAgentId);
        ModelSlot replacement = modelConfig.slots().stream()
                .filter(slot -> slot.enabled() && !slot.id().equalsIgnoreCase(failedSlotId))
                .filter(slot -> slot.capabilities().contains(capability))
                .filter(this::agentSlotAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("操作失败：Agent 角色没有可用的备用模型槽位。"));
        return new Resolution(replacement, "CAPABILITY_RECOVERY", true,
                "角色 " + agent.id() + " 的模型调用失败，已切换到备用槽位 " + replacement.id());
    }

    private BindingView view(AgentModelBinding binding) {
        ModelSlot slot = null;
        try {
            slot = platformSlot(binding.getSlotId());
        } catch (IllegalArgumentException ignored) {
            // 配置删除后仍保留绑定记录，让设置页可提示失效来源并修复。
        }
        String status = !binding.isEnabled() ? "DISABLED"
                : slot == null ? "SLOT_MISSING"
                        : !slot.enabled() ? "SLOT_DISABLED"
                                : agentSlotAvailable(slot) ? "READY" : "CREDENTIAL_MISSING";
        AgentDefinition agent = agentRegistry.resolve(binding.getAgentId());
        return new BindingView(binding.getScope(), binding.getScopeRef(), agent.id(), agent.displayName(),
                slot == null ? binding.getSlotId() : slot.id(), slot == null ? null : slot.name(), binding.isEnabled(),
                slot != null && agentSlotAvailable(slot), status);
    }

    private SlotOption slotOption(ModelSlot slot) {
        return new SlotOption(slot.id(), slot.name(), slot.model(), slot.enabled(), agentSlotAvailable(slot),
                slot.capabilities());
    }

    private ModelSlot platformSlot(String slotId) {
        return modelConfig.slots().stream().filter(slot -> slot.id().equalsIgnoreCase(slotId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：模型槽位不存在（" + slotId + "）。"));
    }

    private List<ScopeRef> visibleScopes(RequestIdentity identity) {
        if (identity == null) {
            return List.of(new ScopeRef(AgentModelBinding.SCOPE_PLATFORM, 0L));
        }
        List<ScopeRef> scopes = new ArrayList<>();
        scopes.add(new ScopeRef(AgentModelBinding.SCOPE_USER, identity.userId()));
        scopes.add(new ScopeRef(AgentModelBinding.SCOPE_ORG, identity.orgId()));
        scopes.add(new ScopeRef(AgentModelBinding.SCOPE_PLATFORM, 0L));
        return scopes;
    }

    private Long checkScope(RequestIdentity identity, String scope) {
        if (identity == null || identity.userId() == null || identity.orgId() == null) {
            throw new IllegalStateException("操作失败：当前执行链路缺少用户身份，请重新登录后重试。");
        }
        if (AgentModelBinding.SCOPE_PLATFORM.equals(scope)) {
            AdminGuard.requirePlatformAdmin();
            return 0L;
        }
        if (AgentModelBinding.SCOPE_ORG.equals(scope)) {
            String role = identity.role();
            if (!AppUser.ROLE_PLATFORM_ADMIN.equals(role) && !AppUser.ROLE_ORG_ADMIN.equals(role)
                    && !"LOCAL".equals(role)) {
                throw new com.example.vatica.controller.ForbiddenException("操作失败：只有组织管理员可以修改组织级 Agent 模型绑定。");
            }
            return identity.orgId();
        }
        return identity.userId();
    }

    private static AgentModelBinding replace(AgentModelBinding old, String slotId) {
        return new AgentModelBinding(old.getId(), old.getScope(), old.getScopeRef(), old.getAgentId(), slotId,
                old.getPriority(), true);
    }

    private static String normalizeScope(String value) {
        String scope = trim(value).toUpperCase(Locale.ROOT);
        if (!scope.equals(AgentModelBinding.SCOPE_USER) && !scope.equals(AgentModelBinding.SCOPE_ORG)
                && !scope.equals(AgentModelBinding.SCOPE_PLATFORM)) {
            throw new IllegalArgumentException("操作失败：scope 仅支持 USER / ORG / PLATFORM。");
        }
        return scope;
    }

    private static int scopePriority(String scope) {
        return switch (scope) {
            case AgentModelBinding.SCOPE_USER -> 300;
            case AgentModelBinding.SCOPE_ORG -> 200;
            default -> 100;
        };
    }

    private boolean credentialAvailable(ModelSlot slot) {
        if (slot.apiKey() != null && !slot.apiKey().isBlank()) {
            return true;
        }
        String url = slot.baseUrl() == null ? "" : slot.baseUrl().toLowerCase(Locale.ROOT);
        return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0")
                || url.contains("ollama");
    }

    private boolean agentSlotAvailable(ModelSlot slot) {
        return ModelSlot.PROTOCOL_OPENAI.equals(slot.protocol()) && credentialAvailable(slot);
    }

    private static String joinReasons(List<String> reasons) {
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record ScopeRef(String scope, Long scopeRef) {
    }
}
