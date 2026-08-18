package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.runtime.AgentRegistry;

/** 迭代 17C：绑定优先级和失效降级的纯单元回归。 */
class AgentModelBindingServiceTest {

    private final AgentModelBindingRepository repository = mock(AgentModelBindingRepository.class);
    private final ModelConfigService modelConfig = mock(ModelConfigService.class);
    private final AgentModelBindingService service = new AgentModelBindingService(repository, modelConfig,
            new AgentRegistry());
    private final RequestIdentity identity = new RequestIdentity(7L, 9L, "LOCAL", "tester");

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void resolvesUserBeforeOrgAndPlatform() {
        ModelSlot user = slot("user-model", "user-key", true);
        ModelSlot org = slot("org-model", "org-key", true);
        ModelSlot platform = slot("platform-model", "platform-key", true);
        when(modelConfig.slots()).thenReturn(List.of(platform, org, user));
        when(repository.findByScopeAndScopeRefAndAgentId("USER", 7L, "document"))
                .thenReturn(Optional.of(binding("USER", 7L, "document", user.id())));
        when(repository.findByScopeAndScopeRefAndAgentId("ORG", 9L, "document"))
                .thenReturn(Optional.of(binding("ORG", 9L, "document", org.id())));
        when(repository.findByScopeAndScopeRefAndAgentId("PLATFORM", 0L, "document"))
                .thenReturn(Optional.of(binding("PLATFORM", 0L, "document", platform.id())));

        AgentModelBindingService.Resolution result = service.resolve(identity, "document", ModelSlot.CAP_CHAT_REASON,
                null);

        assertThat(result.slot().id()).isEqualTo("user-model");
        assertThat(result.source()).isEqualTo("USER");
        assertThat(result.fallback()).isFalse();
    }

    @Test
    void unavailableUserBindingFallsBackToOrgAndExplainsReason() {
        ModelSlot userWithoutKey = slot("user-model", "", false);
        ModelSlot org = slot("org-model", "org-key", true);
        when(modelConfig.slots()).thenReturn(List.of(userWithoutKey, org));
        when(repository.findByScopeAndScopeRefAndAgentId("USER", 7L, "document"))
                .thenReturn(Optional.of(binding("USER", 7L, "document", userWithoutKey.id())));
        when(repository.findByScopeAndScopeRefAndAgentId("ORG", 9L, "document"))
                .thenReturn(Optional.of(binding("ORG", 9L, "document", org.id())));
        when(repository.findByScopeAndScopeRefAndAgentId("PLATFORM", 0L, "document"))
                .thenReturn(Optional.empty());

        AgentModelBindingService.Resolution result = service.resolve(identity, "document", ModelSlot.CAP_CHAT_REASON,
                null);

        assertThat(result.slot().id()).isEqualTo("org-model");
        assertThat(result.source()).isEqualTo("ORG");
        assertThat(result.fallback()).isTrue();
        assertThat(result.fallbackReason()).contains("USER绑定槽位缺少凭据");
    }

    @Test
    void ephemeralCredentialWinsWithoutPersistingBinding() {
        ModelSlot platform = slot("platform-model", "platform-key", true);
        when(modelConfig.slots()).thenReturn(List.of(platform));
        EphemeralCredential credential = new EphemeralCredential("openai", "https://localhost:11434", "local-model",
                0.2, "request-key");

        AgentModelBindingService.Resolution result = service.resolve(identity, "document", ModelSlot.CAP_CHAT_REASON,
                credential);

        assertThat(result.source()).isEqualTo("REQUEST");
        assertThat(result.slot().id()).isEqualTo(credential.toSlot().id());
        assertThat(result.fallback()).isFalse();
    }

    private static AgentModelBinding binding(String scope, Long scopeRef, String agentId, String slotId) {
        return new AgentModelBinding("binding-" + scope + agentId, scope, scopeRef, agentId, slotId, 100, true);
    }

    private static ModelSlot slot(String id, String apiKey, boolean enabled) {
        return new ModelSlot(id, id, ModelSlot.PROTOCOL_OPENAI, "https://api.example.test", apiKey,
                "model-" + id, 0.2, enabled, List.of(ModelSlot.CAP_CHAT_REASON), "");
    }
}
