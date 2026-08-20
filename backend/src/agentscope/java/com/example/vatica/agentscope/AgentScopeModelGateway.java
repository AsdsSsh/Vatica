package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.model.ModelGateway;
import com.example.vatica.model.ModelInvocation;
import com.example.vatica.model.ModelResponse;
import com.example.vatica.model.ModelStreamEvent;
import com.example.vatica.model.ModelUsage;
import com.example.vatica.runtime.AgentRuntime.StepUsage;
import com.example.vatica.usage.DirectModelUsageRecorder;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/** 迭代 22A：AgentScope Model 到 Vatica 模型契约的唯一适配器。 */
@Component
public class AgentScopeModelGateway implements ModelGateway {

    private final ModelRegistry registry;
    private final DirectModelUsageRecorder usageRecorder;

    public AgentScopeModelGateway(ModelRegistry registry, DirectModelUsageRecorder usageRecorder) {
        this.registry = registry;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public ModelResponse call(ModelInvocation invocation) {
        List<ModelStreamEvent> events = stream(invocation).collectList().block();
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ModelUsage usage = ModelUsage.empty();
        if (events != null) {
            for (ModelStreamEvent event : events) {
                if (event.type() == ModelStreamEvent.Type.TEXT && event.content() != null) {
                    text.append(event.content());
                } else if (event.type() == ModelStreamEvent.Type.REASONING && event.content() != null) {
                    reasoning.append(event.content());
                } else if (event.type() == ModelStreamEvent.Type.USAGE && event.usage() != null) {
                    usage = event.usage();
                }
            }
        }
        return new ModelResponse(text.toString(), reasoning.toString(), usage);
    }

    @Override
    public Flux<ModelStreamEvent> stream(ModelInvocation invocation) {
        List<Msg> messages = toAgentScopeMessages(invocation);
        GenerateOptions options = GenerateOptions.builder()
                .stream(true)
                .temperature(invocation.slot().temperature())
                .reasoningEffort(reasoningEffort(invocation.reasoningMode()))
                .build();
        DirectModelUsageRecorder.Reservation reservation = usageRecorder.begin();
        long started = System.nanoTime();
        AtomicReference<ModelUsage> latestUsage = new AtomicReference<>();
        AtomicBoolean settled = new AtomicBoolean();

        Flux<ModelStreamEvent> chunks = registry.agentScopeModel(invocation.slot())
                .stream(messages, List.of(), options)
                .flatMapIterable(response -> toEvents(response, latestUsage));
        return chunks
                .concatWith(Flux.defer(() -> latestUsage.get() == null
                        ? Flux.empty() : Flux.just(ModelStreamEvent.usage(latestUsage.get()))))
                .doFinally(signal -> settle(signal, reservation, latestUsage.get(), started, settled));
    }

    private void settle(SignalType signal, DirectModelUsageRecorder.Reservation reservation,
            ModelUsage usage, long started, AtomicBoolean settled) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        if (signal == SignalType.ON_COMPLETE) {
            StepUsage stepUsage = usage == null ? null
                    : new StepUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                            usage.cachedTokens());
            usageRecorder.complete(reservation, stepUsage, (System.nanoTime() - started) / 1_000_000);
        } else {
            usageRecorder.abort(reservation);
        }
    }

    private static List<ModelStreamEvent> toEvents(ChatResponse response,
            AtomicReference<ModelUsage> latestUsage) {
        List<ModelStreamEvent> events = new ArrayList<>();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null && !text.getText().isEmpty()) {
                events.add(ModelStreamEvent.text(text.getText()));
            } else if (block instanceof ThinkingBlock thinking
                    && thinking.getThinking() != null && !thinking.getThinking().isEmpty()) {
                events.add(ModelStreamEvent.reasoning(thinking.getThinking()));
            }
        }
        if (response.getUsage() != null) {
            latestUsage.set(toUsage(response.getUsage()));
        }
        return events;
    }

    static ModelUsage toUsage(ChatUsage usage) {
        return new ModelUsage(usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(),
                usage.getCachedTokens());
    }

    static List<Msg> toAgentScopeMessages(ModelInvocation invocation) {
        List<Msg> messages = new ArrayList<>(invocation.history().size() + 2);
        if (!invocation.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(invocation.systemPrompt()));
        }
        for (ConversationMessage message : invocation.history()) {
            messages.add(switch (message.role()) {
                case SYSTEM -> new SystemMessage(message.text());
                case USER -> new UserMessage(message.text());
                case ASSISTANT -> new AssistantMessage(message.text());
            });
        }
        if (!invocation.userPrompt().isBlank()) {
            messages.add(new UserMessage(invocation.userPrompt()));
        }
        return List.copyOf(messages);
    }

    static String reasoningEffort(ReasoningMode mode) {
        return switch (mode) {
            case HIGH -> "high";
            case MEDIUM -> "medium";
            case LOW -> "low";
            case DISABLED -> "none";
        };
    }
}
