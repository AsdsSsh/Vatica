package com.example.vatica.agentscope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.vatica.config.AgentScopeContextProperties;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.context.ContextBudgetLedger;
import com.example.vatica.context.TokenEstimator;
import com.example.vatica.runtime.ToolDiscoveryService;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 迭代 30B/30D：AgentScope 每次 ReAct 推理回合的上下文预算与工具执行护栏。
 *
 * <p>AgentScope 2.0.2 没有通用的 token 滑窗或滚动摘要实现；Middleware 是模型调用前
 * 唯一稳定的可替换边界。本类只做无损的整消息裁剪：系统消息和当前用户消息始终保留，
 * 历史从最旧的完整回合开始移除。摘要、关键事实和租户权限仍由 Vatica 在请求入口提供。</p>
 */
public final class AgentScopeContextBudgetMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeContextBudgetMiddleware.class);

    private final Model model;
    private final String systemPrompt;
    private final ContextBudget.CallSite callSite;
    private final int requestedHistoryTokens;
    private final AgentScopeContextProperties properties;
    private final Consumer<ContextBudgetLedger> observer;
    private final int modelWindowOverrideTokens;
    private final ToolDiscoveryService toolDiscovery;
    private final AtomicReference<ContextBudgetLedger> lastLedger = new AtomicReference<>();
    private final AtomicReference<java.util.Set<String>> visibleToolNames = new AtomicReference<>();

    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties) {
        this(model, systemPrompt, callSite, requestedHistoryTokens, properties, ignored -> { }, null);
    }

    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties, Consumer<ContextBudgetLedger> observer) {
        this(model, systemPrompt, callSite, requestedHistoryTokens, properties, observer, null, 0, null);
    }

    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties, Consumer<ContextBudgetLedger> observer,
            java.util.Set<String> initialVisibleToolNames) {
        this(model, systemPrompt, callSite, requestedHistoryTokens, properties, observer,
                initialVisibleToolNames, 0, null);
    }

    /** 迭代 31D：显式能力档案可覆盖未知兼容端点的保守 AgentScope 窗口。 */
    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties, Consumer<ContextBudgetLedger> observer,
            java.util.Set<String> initialVisibleToolNames, int modelWindowOverrideTokens) {
        this(model, systemPrompt, callSite, requestedHistoryTokens, properties, observer,
                initialVisibleToolNames, modelWindowOverrideTokens, null);
    }

    /** 迭代 32B：生产入口可注入混合工具召回器；旧构造器保持纯词法兼容。 */
    public AgentScopeContextBudgetMiddleware(Model model, String systemPrompt,
            ContextBudget.CallSite callSite, int requestedHistoryTokens,
            AgentScopeContextProperties properties, Consumer<ContextBudgetLedger> observer,
            java.util.Set<String> initialVisibleToolNames, int modelWindowOverrideTokens,
            ToolDiscoveryService toolDiscovery) {
        this.model = model;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.callSite = callSite == null ? ContextBudget.CallSite.CHAT : callSite;
        this.requestedHistoryTokens = Math.max(1, requestedHistoryTokens);
        this.properties = properties == null
                ? new AgentScopeContextProperties(true, 0, 0, 0) : properties;
        this.observer = observer == null ? ignored -> { } : observer;
        this.modelWindowOverrideTokens = Math.max(0, modelWindowOverrideTokens);
        this.toolDiscovery = toolDiscovery;
        if (initialVisibleToolNames != null) {
            this.visibleToolNames.set(java.util.Set.copyOf(initialVisibleToolNames));
        }
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
        int modelWindow = modelWindowOverrideTokens > 0 ? modelWindowOverrideTokens
                : effectiveModel == null || effectiveModel.getContextWindowSize() <= 0
                        ? properties.fallbackModelWindowTokens() : effectiveModel.getContextWindowSize();
        int systemTokens = estimateSystem(messages);
        int currentIndex = currentMessageIndex(messages);
        int currentTokens = activeTurnTokens(messages, currentIndex);
        List<ToolSchema> tools = input.tools() == null ? List.of() : List.copyOf(input.tools());
        int toolCapacity = remainingCapacity(modelWindow, properties.outputReserveTokens(),
                properties.safetyMarginTokens(), systemTokens, currentTokens);
        int rawToolTokens = estimateTools(tools);
        String request = currentRequest(messages, currentIndex);
        List<ToolSchema> selected = toolDiscovery == null || !toolDiscovery.enabledValue()
                ? null : toolDiscovery.selectSchemas(tools, request, toolCapacity).selected();
        if (selected != null && (rawToolTokens > toolCapacity || selected.size() < tools.size())) {
            log.warn("AgentScope 工具 Schema 预算收紧：callSite={} window={} supplied={} selected={} capacity={}",
                    callSite, modelWindow, tools.size(), selected.size(), toolCapacity);
            tools = selected;
        } else if (rawToolTokens > toolCapacity) {
            selected = selectToolsWithinBudget(tools, toolCapacity, request);
            log.warn("AgentScope 工具 Schema 预算收紧：callSite={} window={} supplied={} selected={} capacity={}",
                    callSite, modelWindow, tools.size(), selected.size(), toolCapacity);
            tools = selected;
        }
        int toolTokens = estimateTools(tools);
        int available = remainingCapacity(modelWindow, properties.outputReserveTokens(),
                properties.safetyMarginTokens(), systemTokens, toolTokens, currentTokens);
        int historyBudget = Math.min(requestedHistoryTokens, available);
        List<Msg> trimmed = trimHistory(messages, currentIndex, historyBudget);
        int actualHistory = historyTokens(trimmed, currentMessageIndex(trimmed));
        ContextBudgetLedger ledger = new ContextBudgetLedger(callSite, modelWindow,
                requestedHistoryTokens, properties.outputReserveTokens(), properties.safetyMarginTokens(),
                systemTokens, toolTokens, currentTokens, Math.min(historyBudget, actualHistory));
        lastLedger.set(ledger);
        if (input.tools() != null) {
            visibleToolNames.set(toolNames(tools));
        }
        if (ledger.fixedPartExceedsWindow()) {
            // 未知兼容端点的窗口仅是保守估算，不能据此误拒绝用户；明确记录给运维诊断。
            log.warn("AgentScope 固定上下文仍超窗口：callSite={} window={} fixed={} reserved={} tools={}",
                    callSite, ledger.modelWindowTokens(), ledger.fixedInputTokens(), ledger.reservedTokens(),
                    tools.size());
        }
        observer.accept(ledger);
        // input.model() 在自定义 Agent 实现中可能为空；effectiveModel 必须与预算所依据的模型一致。
        // AgentScope 的 null tools 表示“沿用调用方默认工具视图”；不要把它误改成空列表，
        // 否则下一轮可能丢失由 Agent/Toolkit 注入的工具集合。
        List<ToolSchema> outputTools = input.tools() == null ? null : tools;
        return next.apply(new ModelCallInput(trimmed, outputTools, input.options(), effectiveModel));
    }

    /**
     * 工具执行发生在模型调用 Flux 完成之后；在这里临时启用与上一轮模型 Schema 相同的
     * allow/deny 组，避免仅裁剪 Schema 而留下按名称直调的旁路。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext runtimeContext, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        Objects.requireNonNull(next, "AgentScope middleware next 不能为空");
        java.util.Set<String> visible = visibleToolNames.get();
        if (agent == null || agent.getToolkit() == null || visible == null) {
            return next.apply(input);
        }
        AgentScopeToolGroupAdapter.ScopedActivation activation = null;
        try {
            activation = AgentScopeToolGroupAdapter.activate(agent.getToolkit(), visible);
            Flux<AgentEvent> downstream = next.apply(input);
            AgentScopeToolGroupAdapter.ScopedActivation scope = activation;
            return downstream.doFinally(signal -> scope.close());
        } catch (RuntimeException e) {
            if (activation != null) {
                activation.close();
            }
            throw e;
        }
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

    /**
     * 固定输入区先于历史占预算。工具 Schema 自身过大时，只向模型暴露当前请求最相关的可完整放入项；
     * 未选中的工具不会出现在本轮模型输入中，Vatica 既有权限包装仍是最终执行边界。
     */
    private static List<ToolSchema> selectToolsWithinBudget(List<ToolSchema> tools, int capacity,
            String request) {
        if (tools == null || tools.isEmpty() || capacity <= 0) {
            return List.of();
        }
        List<RankedTool> ranked = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            ToolSchema tool = tools.get(index);
            if (tool != null) {
                ranked.add(new RankedTool(index, tool, relevance(tool, request),
                        estimateTools(List.of(tool))));
            }
        }
        ranked.sort(Comparator.comparingInt(RankedTool::score).reversed()
                .thenComparingInt(RankedTool::index));
        boolean hasRelevant = ranked.stream().anyMatch(candidate -> candidate.score() > 0);
        if (!hasRelevant) {
            // 自定义工具名可能没有内置关键词；无法判断意图时保留能放入的最小完整 Schema，
            // 避免超窗裁剪把任务降级成必然无法调用工具的纯文本回答。
            ranked.sort(Comparator.comparingInt(RankedTool::tokens)
                    .thenComparingInt(RankedTool::index));
        }
        int remaining = capacity;
        List<RankedTool> selected = new ArrayList<>();
        for (RankedTool candidate : ranked) {
            // 有明确意图时只保留相关项；无明确意图时走上面的最小 Schema 回退。
            if ((hasRelevant && candidate.score() <= 0) || candidate.tokens() > remaining) {
                continue;
            }
            selected.add(candidate);
            remaining -= candidate.tokens();
        }
        selected.sort(Comparator.comparingInt(RankedTool::index));
        return selected.stream().map(RankedTool::tool).toList();
    }

    private static int relevance(ToolSchema tool, String request) {
        String prompt = lower(request);
        if (prompt.isBlank()) {
            return 0;
        }
        String name = lower(tool.getName());
        String description = lower(tool.getDescription());
        String searchable = name + " " + description;
        int score = !name.isBlank() && prompt.contains(name) ? 100 : 0;
        for (String keyword : keywordsFor(name)) {
            if (prompt.contains(keyword) && searchable.contains(keyword)) {
                score += 20;
            }
        }
        for (String token : prompt.split("[^a-z0-9_]+")) {
            if (token.length() >= 3 && searchable.contains(token)) {
                score += 10;
            }
        }
        score += chineseOverlap(prompt, searchable);
        if (name.contains("calculator") && (prompt.matches(".*\\d.*") || prompt.contains("计算")
                || prompt.contains("预算"))) {
            score += 15;
        }
        return score;
    }

    private static int chineseOverlap(String prompt, String searchable) {
        int score = 0;
        for (int i = 0; i + 1 < prompt.length(); i++) {
            String gram = prompt.substring(i, i + 2);
            if (isCjk(gram.charAt(0)) && isCjk(gram.charAt(1)) && searchable.contains(gram)) {
                score += 5;
                if (score >= 25) {
                    break;
                }
            }
        }
        return score;
    }

    private static boolean isCjk(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static List<String> keywordsFor(String toolName) {
        if (toolName.contains("file") || toolName.contains("workspace")) {
            return List.of("文件", "目录", "路径", "文件夹", "读取", "写入", "保存");
        }
        if (toolName.contains("calendar")) {
            return List.of("日程", "日历", "会议", "预约");
        }
        if (toolName.contains("todo")) {
            return List.of("待办", "提醒", "清单", "完成");
        }
        if (toolName.contains("mail")) {
            return List.of("邮件", "邮箱", "收件", "发送");
        }
        if (toolName.contains("knowledge") || toolName.contains("search")) {
            return List.of("知识库", "资料", "检索", "搜索", "文档");
        }
        if (toolName.contains("word") || toolName.contains("report")) {
            return List.of("周报", "报告", "文档", "总结");
        }
        if (toolName.contains("excel") || toolName.contains("stats")) {
            return List.of("表格", "统计", "数据", "excel", "字数");
        }
        if (toolName.contains("calculator")) {
            return List.of("计算", "预算", "金额", "数值");
        }
        return List.of();
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

    private static java.util.Set<String> toolNames(List<ToolSchema> tools) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (ToolSchema tool : tools) {
            if (tool != null && tool.getName() != null && !tool.getName().isBlank()) {
                names.add(tool.getName());
            }
        }
        return java.util.Set.copyOf(names);
    }

    private static int remainingCapacity(int window, int... consumed) {
        long remaining = Math.max(0, window);
        for (int value : consumed) {
            remaining -= Math.max(0, value);
        }
        if (remaining <= 0) {
            return 0;
        }
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    private static String currentRequest(List<Msg> messages, int currentIndex) {
        if (currentIndex < 0 || currentIndex >= messages.size()) {
            return "";
        }
        Msg current = messages.get(currentIndex);
        return current == null || current.getTextContent() == null ? "" : current.getTextContent();
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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record RankedTool(int index, ToolSchema tool, int score, int tokens) {
    }
}
