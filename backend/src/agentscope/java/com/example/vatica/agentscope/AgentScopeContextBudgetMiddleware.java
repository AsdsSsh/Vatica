package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextBudgetLedger;
import com.example.vatica.context.TokenEstimator;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

/**
 * 迭代 30B：AgentScope 每次 ReAct 推理回合的上下文预算护栏。
 *
 * <p>AgentScope 2.0.2 没有通用的 token 滑窗或滚动摘要实现；Middleware 是模型调用前
 * 唯一稳定的可替换边界。本类只做无损的整消息裁剪：系统消息和当前用户消息始终保留，
 * 历史从最旧的完整回合开始移除。摘要、关键事实和租户权限仍由 Vatica 在请求入口提供。</p>
 */
public final class AgentScopeContextBudgetMiddleware implements MiddlewareBase {

    private final Model model;
    private final String systemPrompt;
    private final ContextBudget.CallSite callSite;
    private final int requestedHistoryTokens;
    private final AgentScopeContextProperties properties;
    private final Consumer<ContextBudgetLedger> observer;
    private final AtomicReference<ContextBudgetLedger> lastLedger = new AtomicReference<>();

    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties) {
        this(model, systemPrompt, callSite, requestedHistoryTokens, properties, ignored -> { });
    }

    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties, Consumer<ContextBudgetLedger> observer) {
        this.model = model;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.callSite = callSite == null ? ContextBudget.CallSite.CHAT : callSite;
        this.requestedHistoryTokens = Math.max(1, requestedHistoryTokens);
        this.properties = properties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : properties;
        this.observer = observer == null ? ignored -> { } : observer;
    }

    /** AgentScope 的非废弃模型调用中间件入口；每个 ReAct 回合都会经过这里。 */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext runtimeContext,
            ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        Objects.requireNonNull(next, "AgentScope middleware next 不能为空");
        if (!properties.enabledValue() || input == null) {
            return next.apply(input);
        }
        List<Msg> messages = input.messages() == null ? List.of() : List.copyOf(input.messages());
        Model effectiveModel = input.model() == null ? model : input.model();
        int modelWindow = effectiveModel == null || effectiveModel.getContextWindowSize() <= 0
                ? properties.fallbackModelWindowTokens() : effectiveModel.getContextWindowSize();
        int systemTokens = estimateSystem(messages);
        int toolTokens = estimateTools(input.tools());
        int currentIndex = currentMessageIndex(messages);
        int currentTokens = activeTurnTokens(messages, currentIndex);
        int available = Math.max(0, modelWindow - properties.outputReserveTokens()
                - properties.safetyMarginTokens() - systemTokens - toolTokens - currentTokens);
        int historyBudget = Math.min(requestedHistoryTokens, available);
        List<Msg> trimmed = trimHistory(messages, currentIndex, historyBudget);
        int actualHistory = historyTokens(trimmed, currentMessageIndex(trimmed));
        ContextBudgetLedger ledger = new ContextBudgetLedger(callSite, modelWindow,
                requestedHistoryTokens, properties.outputReserveTokens(), properties.safetyMarginTokens(),
                systemTokens, toolTokens, currentTokens, Math.min(historyBudget, actualHistory));
        lastLedger.set(ledger);
        observer.accept(ledger);
        // input.model() 在自定义 Agent 实现中可能为空；effectiveModel 必须与预算所依据的模型一致。
        return next.apply(new ModelCallInput(trimmed, input.tools(), input.options(), effectiveModel));
    }

    public ContextBudgetLedger lastLedger() {
        return lastLedger.get();
    }

    /**
     * 从最旧端移除完整的用户回合；系统消息和当前用户消息永不删除，单条消息不截断。
     * 方法包可见，便于不依赖网络模型的确定性回归测试。
     */
    static List<Msg> trimHistory(List<Msg> input, int currentIndex, int historyBudgetTokens) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<Msg> working = new ArrayList<>(input);
        int current = normalizeCurrentIndex(working, currentIndex);
        int budget = Math.max(0, historyBudgetTokens);
        while (historyTokens(working, current) > budget) {
            int first = firstRemovableIndex(working, current);
            if (first < 0) {
                break;
            }
            int end = removalEnd(working, first, current);
            int removed = removeRangeExceptSystem(working, first, end);
            current -= removed;
            if (current < 0) {
                current = currentMessageIndex(working);
            }
            if (working.isEmpty()) {
                break;
            }
        }
        return List.copyOf(working);
    }

    private int estimateSystem(List<Msg> messages) {
        int fromPrompt = TokenEstimator.estimate(systemPrompt);
        int fromMessages = 0;
        for (Msg message : messages) {
            if (isSystem(message)) {
                fromMessages = addSaturated(fromMessages, estimate(message));
            }
        }
        // AgentScope normally materializes sysPrompt as a system message. Use the larger value
        // to remain conservative when a custom Agent implementation does not expose it.
        return Math.max(fromPrompt, fromMessages);
    }

    private static int estimateTools(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        List<String> descriptions = new ArrayList<>(tools.size() * 4);
        for (ToolSchema tool : tools) {
            if (tool == null) {
                continue;
            }
            descriptions.add(tool.getName());
            descriptions.add(tool.getDescription());
            descriptions.add(String.valueOf(tool.getParameters()));
            descriptions.add(String.valueOf(tool.getOutputSchema()));
        }
        int total = 0;
        for (String description : descriptions) {
            total = addSaturated(total, TokenEstimator.estimate(description));
        }
        return total;
    }

    private static int currentMessageIndex(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() != null
                    && "USER".equalsIgnoreCase(message.getRole().name())) {
                return i;
            }
        }
        return messages.isEmpty() ? -1 : messages.size() - 1;
    }

    private static int normalizeCurrentIndex(List<Msg> messages, int requestedIndex) {
        if (messages.isEmpty()) {
            return -1;
        }
        return requestedIndex >= 0 && requestedIndex < messages.size()
                ? requestedIndex : currentMessageIndex(messages);
    }

    private static int historyTokens(List<Msg> messages, int currentIndex) {
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            if ((currentIndex >= 0 && i >= currentIndex) || isSystem(messages.get(i))) {
                continue;
            }
            total = addSaturated(total, estimate(messages.get(i)));
        }
        return total;
    }

    private static int firstRemovableIndex(List<Msg> messages, int currentIndex) {
        int end = currentIndex < 0 ? messages.size() : currentIndex;
        for (int i = 0; i < end; i++) {
            if (isSystem(messages.get(i))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static int removalEnd(List<Msg> messages, int first, int currentIndex) {
        // A turn starts at a user message and extends up to (but not including) the next user.
        // For an orphan assistant/tool prefix, remove only that prefix until the next user.
        int end = currentIndex < 0 ? messages.size() : currentIndex;
        for (int i = first + 1; i < end; i++) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() != null
                    && "USER".equalsIgnoreCase(message.getRole().name())) {
                return i;
            }
        }
        return end;
    }

    private static int removeRangeExceptSystem(List<Msg> messages, int start, int end) {
        int removed = 0;
        for (int i = Math.min(end, messages.size()) - 1; i >= Math.max(0, start); i--) {
            if (!isSystem(messages.get(i))) {
                messages.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private static int activeTurnTokens(List<Msg> messages, int currentIndex) {
        if (currentIndex < 0 || currentIndex >= messages.size()) {
            return 0;
        }
        int total = 0;
        for (int i = currentIndex; i < messages.size(); i++) {
            if (!isSystem(messages.get(i))) {
            total = addSaturated(total, estimate(messages.get(i)));
            }
        }
        return total;
    }

    private static boolean isSystem(Msg message) {
        return message != null && message.getRole() != null
                && "SYSTEM".equalsIgnoreCase(message.getRole().name());
    }

    private static int estimate(Msg message) {
        if (message == null) {
            return 0;
        }
        String text = message.getTextContent();
        if (text == null || text.isBlank()) {
            text = String.valueOf(message.getContent());
        }
        return addSaturated(TokenEstimator.estimate(text), 4);
    }

    private static int addSaturated(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
