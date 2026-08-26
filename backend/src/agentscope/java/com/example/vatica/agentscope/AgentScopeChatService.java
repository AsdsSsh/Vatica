package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.model.ModelUsage;
import com.example.vatica.runtime.AgentRuntime.StepUsage;
import com.example.vatica.usage.DirectModelUsageRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.AgentTool;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/** 迭代 22A：聊天主链使用 AgentScope ReActAgent，业务层只消费 Vatica 事件。 */
@Component
public class AgentScopeChatService {

    private final ModelRegistry registry;
    private final ObjectMapper mapper;
    private final DirectModelUsageRecorder usageRecorder;
    private final ContextBudget contextBudget;
    private final AgentScopeContextProperties contextProperties;

    public AgentScopeChatService(ModelRegistry registry, ObjectMapper mapper,
            DirectModelUsageRecorder usageRecorder) {
        this(registry, mapper, usageRecorder, new ContextBudget(0, 0, 0, 0, 0),
                new AgentScopeContextProperties(true, 0, 0, 0));
    }

    @Autowired
    public AgentScopeChatService(ModelRegistry registry, ObjectMapper mapper,
            DirectModelUsageRecorder usageRecorder, ContextBudget contextBudget,
            AgentScopeContextProperties contextProperties) {
        this.registry = registry;
        this.mapper = mapper;
        this.usageRecorder = usageRecorder;
        this.contextBudget = contextBudget == null ? new ContextBudget(0, 0, 0, 0, 0) : contextBudget;
        this.contextProperties = contextProperties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : contextProperties;
    }

    public ChatResult call(ChatRequest request) {
        ReActAgent agent = buildAgent(request);
        DirectModelUsageRecorder.Reservation reservation = usageRecorder.begin();
        long started = System.nanoTime();
        try {
            Msg reply = agent.call(messages(request), context(request)).block();
            ModelUsage usage = reply == null || reply.getChatUsage() == null
                    ? ModelUsage.empty() : AgentScopeModelGateway.toUsage(reply.getChatUsage());
            usageRecorder.complete(reservation, stepUsage(usage), elapsed(started));
            return new ChatResult(reply == null ? "" : reply.getTextContent(), "", usage);
        } catch (RuntimeException e) {
            usageRecorder.abort(reservation);
            throw e;
        } finally {
            agent.close();
        }
    }

    public Flux<ChatEvent> stream(ChatRequest request) {
        ReActAgent agent = buildAgent(request);
        DirectModelUsageRecorder.Reservation reservation = usageRecorder.begin();
        long started = System.nanoTime();
        UsageAccumulator usage = new UsageAccumulator();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean emittedText = new AtomicBoolean();

        Flux<ChatEvent> visible = agent.streamEvents(messages(request), context(request))
                .handle((event, sink) -> mapEvent(event, sink, usage, emittedText));
        return visible
                .concatWith(Flux.defer(() -> Flux.just(ChatEvent.usage(usage.snapshot()))))
                .doFinally(signal -> {
                    try {
                        settle(signal, reservation, usage.snapshot(), started, settled);
                    } finally {
                        agent.close();
                    }
                });
    }

    private ReActAgent buildAgent(ChatRequest request) {
        Toolkit toolkit = new Toolkit();
        // 聊天工具已经过 Vatica 权限/重试/Trace 包装；空白名单保留全部候选工具，
        // 但统一经适配器去重，避免同名 MCP 工具让模型看到不稳定的 Schema。
        AgentScopeToolGroupAdapter.register(toolkit, request.tools(), Set.of());
        GenerateOptions options = GenerateOptions.builder()
                .stream(request.streaming())
                .temperature(request.slot().temperature())
                .reasoningEffort(AgentScopeModelGateway.reasoningEffort(request.reasoningMode()))
                .build();
        var model = registry.agentScopeModel(request.slot());
        AgentScopeContextBudgetMiddleware budgetMiddleware = new AgentScopeContextBudgetMiddleware(model,
                request.systemPrompt(), ContextBudget.CallSite.CHAT,
                contextBudget.tokensFor(ContextBudget.CallSite.CHAT), contextProperties);
        return ReActAgent.builder()
                .name("vatica-chat")
                .sysPrompt(request.systemPrompt())
                .model(model)
                .toolkit(toolkit)
                // 迭代 30B：每次 AgentScope 模型调用（含 ReAct 后续回合）都重新执行预算裁剪。
                .middleware(budgetMiddleware)
                .maxIters(8)
                .defaultSessionId(request.sessionId())
                .generateOptions(options)
                .build();
    }

    private static List<Msg> messages(ChatRequest request) {
        List<Msg> messages = new ArrayList<>(request.history().size() + 1);
        for (ConversationMessage message : request.history()) {
            if (message.role() == ConversationMessage.Role.USER) {
                messages.add(new UserMessage(message.text()));
            } else if (message.role() == ConversationMessage.Role.ASSISTANT) {
                messages.add(new AssistantMessage(message.text()));
            }
        }
        messages.add(new UserMessage(request.userPrompt()));
        return List.copyOf(messages);
    }

    private static RuntimeContext context(ChatRequest request) {
        return RuntimeContext.builder()
                .userId(String.valueOf(request.identity().userId()))
                .sessionId(request.sessionId())
                .build();
    }

    private static void mapEvent(AgentEvent event, reactor.core.publisher.SynchronousSink<ChatEvent> sink,
            UsageAccumulator usage, AtomicBoolean emittedText) {
        if (event instanceof TextBlockDeltaEvent text && text.getDelta() != null && !text.getDelta().isEmpty()) {
            emittedText.set(true);
            sink.next(ChatEvent.text(text.getDelta()));
        } else if (event instanceof ThinkingBlockDeltaEvent thinking
                && thinking.getDelta() != null && !thinking.getDelta().isEmpty()) {
            sink.next(ChatEvent.reasoning(thinking.getDelta()));
        } else if (event instanceof ModelCallEndEvent modelEnd && modelEnd.getUsage() != null) {
            usage.add(AgentScopeModelGateway.toUsage(modelEnd.getUsage()));
        } else if (event instanceof AgentResultEvent result && !emittedText.get()
                && result.getResult() != null && result.getResult().getTextContent() != null
                && !result.getResult().getTextContent().isEmpty()) {
            emittedText.set(true);
            sink.next(ChatEvent.text(result.getResult().getTextContent()));
        }
    }

    private void settle(SignalType signal, DirectModelUsageRecorder.Reservation reservation,
            ModelUsage usage, long started, AtomicBoolean settled) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        if (signal == SignalType.ON_COMPLETE) {
            usageRecorder.complete(reservation, stepUsage(usage), elapsed(started));
        } else {
            usageRecorder.abort(reservation);
        }
    }

    private static StepUsage stepUsage(ModelUsage usage) {
        return new StepUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.cachedTokens());
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public record ChatRequest(ModelSlot slot, ReasoningMode reasoningMode, String systemPrompt,
            List<ConversationMessage> history, String userPrompt, AgentTool[] tools,
            RequestIdentity identity, String sessionId, boolean streaming) {
        public ChatRequest {
            reasoningMode = reasoningMode == null ? ReasoningMode.DISABLED : reasoningMode;
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            history = history == null ? List.of() : List.copyOf(history);
            userPrompt = userPrompt == null ? "" : userPrompt;
            tools = tools == null ? new AgentTool[0] : tools.clone();
            sessionId = sessionId == null || sessionId.isBlank()
                    ? "chat-" + UUID.randomUUID() : sessionId;
        }

        @Override
        public AgentTool[] tools() {
            return tools.clone();
        }
    }

    public record ChatResult(String content, String reasoning, ModelUsage usage) {
    }

    public record ChatEvent(Type type, String content, ModelUsage usage) {
        public enum Type {
            TEXT,
            REASONING,
            USAGE
        }

        static ChatEvent text(String content) {
            return new ChatEvent(Type.TEXT, content, null);
        }

        static ChatEvent reasoning(String content) {
            return new ChatEvent(Type.REASONING, content, null);
        }

        static ChatEvent usage(ModelUsage usage) {
            return new ChatEvent(Type.USAGE, null, usage);
        }
    }

    private static final class UsageAccumulator {
        private int input;
        private int output;
        private int total;
        private int cached;

        private void add(ModelUsage value) {
            input += value.inputTokens();
            output += value.outputTokens();
            total += value.totalTokens();
            cached += value.cachedTokens();
        }

        private ModelUsage snapshot() {
            return new ModelUsage(input, output, total, cached);
        }
    }
}
