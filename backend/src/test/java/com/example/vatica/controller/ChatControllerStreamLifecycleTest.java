package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.agentscope.AgentScopeChatService;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.context.ChatContextService;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.event.SseEventGateway;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.PermissionEventPublisher;
import com.example.vatica.tool.AgentToolProvider;
import com.example.vatica.usage.UsageContext;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 31D：聊天续传与上下文准备异常必须保持一次执行和确定性清理。 */
class ChatControllerStreamLifecycleTest {

    private static final RequestIdentity IDENTITY = new RequestIdentity(7L, 3L, "MEMBER", "tester");

    private final SseEventGateway gateway = new SseEventGateway(new ObjectMapper());

    @AfterEach
    void clearState() {
        RequestIdentityContext.clear();
        UsageContext.clear();
    }

    @Test
    void lastEventIdOnlyResubscribesWithoutStartingAnotherModelCall() {
        RequestIdentityContext.set(IDENTITY);
        String channel = TenantChannels.chat(IDENTITY, "resume-session");
        gateway.publish(channel, "chat_text", "已生成片段");
        AgentScopeChatService chatService = mock(AgentScopeChatService.class);
        ChatContextService contextService = mock(ChatContextService.class);
        ChatController controller = controller(chatService, contextService,
                mock(FilePermissionRequestService.class));

        controller.stream(new ChatRequest("同一条问题", "resume-session", null, null), "1");

        verify(chatService, never()).stream(any());
        verifyNoInteractions(contextService);
        assertThat(gateway.subscriberCount(channel)).isOne();
        assertThat(controller.activeStreamCount()).isOne();
        assertThat(UsageContext.current()).isNull();
    }

    @Test
    void contextPreparationFailureRemovesEmitterSubscriberAndUsageContext() {
        RequestIdentityContext.set(IDENTITY);
        AgentScopeChatService chatService = mock(AgentScopeChatService.class);
        ChatContextService contextService = mock(ChatContextService.class);
        when(contextService.prepare(any())).thenThrow(new IllegalStateException("database unavailable"));
        FilePermissionRequestService permissionRequests = mock(FilePermissionRequestService.class);
        ChatController controller = controller(chatService, contextService, permissionRequests);

        controller.stream(new ChatRequest("请回答", "failed-session", null, null), null);

        String channel = TenantChannels.chat(IDENTITY, "failed-session");
        assertThat(controller.activeStreamCount()).isZero();
        assertThat(gateway.subscriberCount(channel)).isZero();
        assertThat(UsageContext.current()).isNull();
        verify(chatService, never()).stream(any());
        verify(permissionRequests).cancelChannel(channel);
    }

    private ChatController controller(AgentScopeChatService chatService, ChatContextService contextService,
            FilePermissionRequestService permissionRequests) {
        ModelRegistry registry = mock(ModelRegistry.class);
        ModelSlot slot = new ModelSlot("test", "Test", "openai", "http://localhost", "", "test", 0.2, true);
        when(registry.defaultSlot()).thenReturn(slot);
        AgentToolProvider tools = () -> new io.agentscope.core.tool.AgentTool[0];
        return new ChatController(registry,
                new ChatProperties(new ChatProperties.Sse(Duration.ofMinutes(1)), null, null),
                mock(SessionMemory.class), tools, mock(PermissionEventPublisher.class), permissionRequests,
                new ObjectMapper(), new ContextBudget(0, 0, 0, 0, 0), gateway, chatService, null,
                new com.example.vatica.agentscope.AgentScopeContextBudgetPlanner(
                        new AgentScopeContextProperties(true, 0, 0, 0)), contextService);
    }
}
