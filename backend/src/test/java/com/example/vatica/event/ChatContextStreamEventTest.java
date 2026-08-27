package com.example.vatica.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.agentscope.AgentScopeChatService;
import com.example.vatica.agentscope.AgentScopeChatService.ChatEvent;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ContextAllocationProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.context.ChatContextService;
import com.example.vatica.context.ChatContextStatus;
import com.example.vatica.context.ContextAllocationPlan;
import com.example.vatica.context.ContextAllocationPlanner;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextFixedInput;
import com.example.vatica.context.ContextMode;
import com.example.vatica.context.ConversationEvidenceStatus;
import com.example.vatica.context.ModelCapabilityProfile;
import com.example.vatica.controller.ChatController;
import com.example.vatica.controller.ChatRequest;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.PermissionEventPublisher;
import com.example.vatica.tool.AgentToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

/** 迭代 31D：流式聊天必须在第一段模型正文前发送请求级上下文状态。 */
class ChatContextStreamEventTest {

    private static final RequestIdentity IDENTITY = new RequestIdentity(7L, 3L, "MEMBER", "tester");

    private final SseEventGateway gateway = new SseEventGateway(new ObjectMapper());

    @AfterEach
    void clearState() {
        RequestIdentityContext.clear();
        gateway.shutdown();
    }

    @Test
    void publishesInitialContextBeforeChatText() {
        RequestIdentityContext.set(IDENTITY);
        ModelRegistry registry = mock(ModelRegistry.class);
        ModelSlot slot = new ModelSlot("test", "Test", "openai", "http://localhost", "", "test", 0.2, true);
        when(registry.defaultSlot()).thenReturn(slot);
        io.agentscope.core.model.Model model = mock(io.agentscope.core.model.Model.class);
        when(model.getContextWindowSize()).thenReturn(128_000);
        when(registry.agentScopeModel(slot)).thenReturn(model);

        ContextAllocationPlan plan = new ContextAllocationPlanner(new ContextAllocationProperties()).plan(
                new ModelCapabilityProfile("test", 128_000, 16_000,
                        ModelCapabilityProfile.ESTIMATED_TOKENIZER, true),
                ContextMode.NORMAL, ContextFixedInput.empty());
        ChatContextStatus status = new ChatContextStatus(ContextMode.NORMAL, ContextMode.NORMAL,
                plan.modelWindowTokens(), plan.plannedInputTokens(), plan.dynamicBudget(),
                ConversationEvidenceStatus.SKIPPED_MODE, 0, 0, false, false,
                "LOADED", 0, 0, 0, 0, false);
        ChatContextService contextService = mock(ChatContextService.class);
        when(contextService.prepare(any())).thenReturn(new ChatContextService.PreparedChatContext(
                List.of(), plan, status));

        AgentScopeChatService chatService = mock(AgentScopeChatService.class);
        when(chatService.stream(any())).thenReturn(Flux.just(
                new ChatEvent(ChatEvent.Type.TEXT, "第一段回复", null)));
        AgentToolProvider tools = () -> new io.agentscope.core.tool.AgentTool[0];
        ChatController controller = new ChatController(registry,
                new ChatProperties(new ChatProperties.Sse(Duration.ofMinutes(1)), null, null),
                mock(SessionMemory.class), tools, mock(PermissionEventPublisher.class),
                mock(FilePermissionRequestService.class), new ObjectMapper(),
                new ContextBudget(0, 0, 0, 0, 0), gateway, chatService, null,
                new com.example.vatica.agentscope.AgentScopeContextBudgetPlanner(
                        new AgentScopeContextProperties(true, 0, 0, 0)), contextService);

        controller.stream(new ChatRequest("请回答", "session-31d", null, null));

        List<SseEventGateway.EventEnvelope> events = gateway.history(TenantChannels.chat(IDENTITY, "session-31d"));
        assertThat(events).extracting(SseEventGateway.EventEnvelope::type)
                .containsExactly("context", "chat_text");
        assertThat(events.getFirst().data()).isSameAs(status);
        assertThat(events.get(1).data()).isEqualTo("第一段回复");
    }
}
