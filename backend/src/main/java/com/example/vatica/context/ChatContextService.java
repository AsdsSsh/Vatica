package com.example.vatica.context;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.controller.SessionContextReadRequest;
import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.model.ConversationMessage;

import io.agentscope.core.tool.AgentTool;

/**
 * 迭代 31D：聊天入口的请求级分层上下文编排。
 *
 * <p>模型能力和模式先生成预算计划，再受控回源近期原文；历史证据只在有效的长任务/深度审阅
 * 模式中检索。任何事实、会话或证据读取失败都会退化为更少的上下文，而不是阻断主聊天链路。</p>
 */
@Service
public class ChatContextService {

    private static final Logger log = LoggerFactory.getLogger(ChatContextService.class);

    private final SessionMemory sessionMemory;
    private final ContextFactService contextFacts;
    private final ConversationEvidenceRetriever evidenceRetriever;
    private final ContextAllocationPlanner allocationPlanner;
    private final ChatProperties chatProperties;
    private final ContextOperationalMaterialService operationalMaterials;

    /** 兼容没有操作材料读取器的历史测试替身。 */
    public ChatContextService(SessionMemory sessionMemory, ContextFactService contextFacts,
            ConversationEvidenceRetriever evidenceRetriever, ContextAllocationPlanner allocationPlanner,
            ChatProperties chatProperties) {
        this(sessionMemory, contextFacts, evidenceRetriever, allocationPlanner, chatProperties, null);
    }

    @Autowired
    public ChatContextService(SessionMemory sessionMemory, ContextFactService contextFacts,
            ConversationEvidenceRetriever evidenceRetriever, ContextAllocationPlanner allocationPlanner,
            ChatProperties chatProperties, ContextOperationalMaterialService operationalMaterials) {
        this.sessionMemory = sessionMemory;
        this.contextFacts = contextFacts;
        this.evidenceRetriever = evidenceRetriever;
        this.allocationPlanner = allocationPlanner;
        this.chatProperties = chatProperties;
        this.operationalMaterials = operationalMaterials;
    }

    public PreparedChatContext prepare(PreparationRequest request) {
        PreparationRequest source = request == null ? PreparationRequest.empty() : request;
        String sessionId = sessionIdOf(source.sessionId());
        ContextMode requestedMode = ContextMode.normalize(source.contextMode());
        ContextFixedInput fixed = new ContextFixedInput(
                TokenEstimator.estimate(source.systemPrompt()), estimateToolSchemas(source.tools()),
                TokenEstimator.estimate(source.userPrompt()), 0, 0);
        ContextAllocationPlan initialPlan = allocationPlanner.plan(source.modelCapability(), requestedMode, fixed);

        FactRead factRead = factsFor(sessionId);
        List<ContextFactService.ContextFactSnippet> facts = factRead.snippets();
        // 先按模式的近期原文上限做一次受控探测。不能用第一次“全策略分配”后的 recent 额度：
        // 小窗口下事实/摘要的理论配额会先占满动态 cap，导致没有实际事实时也错误读到 0 条历史。
        int probeHistoryTokens = Math.min(initialPlan.requestedDynamic().recentHistoryTokens(),
                initialPlan.modeDynamicCapTokens());
        ReadWindow read = readWindow(sessionId, probeHistoryTokens);
        boolean degradedBeforeEvidence = isDegraded(read, factRead);
        ContextOperationalMaterials operational = operationalFor(source, sessionId, degradedBeforeEvidence);
        ContextAllocationPlan plan = allocationPlanner.plan(source.modelCapability(), requestedMode, fixed,
                demandFor(initialPlan, read.window(), facts, operational));

        // 事实或摘要实际较小时，二次规划会把空出的额度让给近期原文；只重新读取本次请求视图。
        if (plan.dynamicBudget().recentHistoryTokens() != initialPlan.dynamicBudget().recentHistoryTokens()
                && !"UNAVAILABLE".equals(read.status())) {
            read = readWindow(sessionId, plan.dynamicBudget().recentHistoryTokens());
            plan = allocationPlanner.plan(source.modelCapability(), requestedMode, fixed,
                    demandFor(plan, read.window(), facts, operational));
        }

        ConversationEvidenceResult evidence = evidenceFor(sessionId, source.userPrompt(), read.window(), plan);
        boolean degraded = degradedBeforeEvidence || evidence.status() == ConversationEvidenceStatus.UNAVAILABLE;
        if (degraded && !operational.shouldInject()) {
            // 证据失败才暴露的降级要真实读取操作事实，而不是注入“无可用记录”空壳。
            operational = operationalFor(source, sessionId, true);
        }
        List<ConversationMessage> history = assemble(read.window(), facts, operational, evidence, plan);
        int historyTokens = ContextTrimmer.estimateTokens(history);
        ChatContextStatus status = new ChatContextStatus(plan.requestedMode(), plan.effectiveMode(),
                plan.modelWindowTokens(), plan.plannedInputTokens(), plan.dynamicBudget(), evidence.status(),
                evidence.candidateCount(), evidence.estimatedTokens(), plan.modeDowngraded(),
                plan.dynamicConstrained(), read.status(), historyTokens, 0, 0, 0, false);
        log.debug("聊天上下文装配：requested={} effective={} window={} history={} evidence={} candidates={} operational={} status={}",
                status.requestedMode(), status.effectiveMode(), status.modelWindowTokens(), historyTokens,
                status.evidenceTokens(), status.evidenceCandidateCount(), operational.snippets().size(),
                status.evidenceStatus());
        return new PreparedChatContext(history, plan, status);
    }

    private FactRead factsFor(String sessionId) {
        if (contextFacts == null) {
            return FactRead.empty();
        }
        try {
            return new FactRead(contextFacts.resolveForChat(sessionId), false);
        } catch (RuntimeException exception) {
            log.warn("关键事实读取失败，降级为会话历史：type={}", exception.getClass().getSimpleName());
            return new FactRead(List.of(), true);
        }
    }

    private ContextOperationalMaterials operationalFor(PreparationRequest request, String sessionId,
            boolean degraded) {
        boolean hasExplicitTask = request.taskId() != null && !request.taskId().isBlank();
        if (operationalMaterials == null || (!degraded && !hasExplicitTask)) {
            return ContextOperationalMaterials.empty(false);
        }
        try {
            return operationalMaterials.resolveForChat(sessionId, request.taskId(), degraded || hasExplicitTask);
        } catch (RuntimeException exception) {
            log.warn("操作事实读取失败，继续使用降级上下文：type={}", exception.getClass().getSimpleName());
            return new ContextOperationalMaterials(List.of(), degraded || hasExplicitTask, true);
        }
    }

    private static boolean isDegraded(ReadWindow read, FactRead facts) {
        ContextWindow window = read.window();
        return facts.failed() || !"LOADED".equals(read.status()) || window.hasFallbackHistory()
                || window.summaryStatus() == com.example.vatica.controller.SessionSummaryStatus.FAILED;
    }

    private ReadWindow readWindow(String sessionId, int tokenBudget) {
        ContextWindow empty = emptyWindow();
        if (sessionMemory == null) {
            return new ReadWindow(empty, "UNAVAILABLE");
        }
        try {
            ContextWindow window = sessionMemory.contextWindow(sessionId,
                    new SessionContextReadRequest(tokenBudget, longContextMaxMessages()));
            return new ReadWindow(window == null ? empty : window, "LOADED");
        } catch (RuntimeException exception) {
            log.warn("长上下文读取失败，尝试热窗口降级：type={}", exception.getClass().getSimpleName());
            try {
                // JPA 实现的 hotContextWindow 只读 JVM 缓存，数据库故障时不能再次回源。
                ContextWindow window = sessionMemory.hotContextWindow(sessionId);
                return new ReadWindow(window == null ? empty : window, "FALLBACK");
            } catch (RuntimeException fallbackException) {
                log.warn("热窗口读取失败，使用空上下文：type={}", fallbackException.getClass().getSimpleName());
                return new ReadWindow(empty, "UNAVAILABLE");
            }
        }
    }

    private ContextDynamicDemand demandFor(ContextAllocationPlan base, ContextWindow window,
            List<ContextFactService.ContextFactSnippet> facts, ContextOperationalMaterials operational) {
        ContextDynamicDemand target = base.requestedDynamic();
        int factsTokens = saturatedAdd(ContextAssembler.estimateFactTokens(facts),
                operational == null || !operational.shouldInject() ? 0 : operational.estimatedTokens());
        int summaryTokens = window.summary() == null ? 0
                : TokenEstimator.estimate("【历史会话摘要】\n" + window.summary());
        int recentTokens = estimateMessages(window.uncoveredHead()) + estimateMessages(window.uncoveredTail())
                + estimateMessages(window.recent());
        boolean canRetrieveEvidence = base.effectiveMode() != ContextMode.NORMAL
                && window.recentStartSeq() > 1;
        return new ContextDynamicDemand(factsTokens, summaryTokens, recentTokens,
                canRetrieveEvidence ? target.historicalEvidenceTokens() : 0, 0);
    }

    private ConversationEvidenceResult evidenceFor(String sessionId, String query, ContextWindow window,
            ContextAllocationPlan plan) {
        if (plan.effectiveMode() == ContextMode.NORMAL) {
            return ConversationEvidenceResult.empty(ConversationEvidenceStatus.SKIPPED_MODE);
        }
        int budget = plan.dynamicBudget().historicalEvidenceTokens();
        if (window.recentStartSeq() <= 1) {
            return ConversationEvidenceResult.empty(ConversationEvidenceStatus.SKIPPED_NO_HISTORY);
        }
        if (budget <= 0) {
            return ConversationEvidenceResult.empty(ConversationEvidenceStatus.SKIPPED_BUDGET);
        }
        if (evidenceRetriever == null) {
            return ConversationEvidenceResult.empty(ConversationEvidenceStatus.UNAVAILABLE);
        }
        try {
            return evidenceRetriever.retrieve(sessionId, query, window.recentStartSeq(), budget);
        } catch (RuntimeException exception) {
            log.warn("会话证据读取失败，降级继续聊天：type={}", exception.getClass().getSimpleName());
            return ConversationEvidenceResult.empty(ConversationEvidenceStatus.UNAVAILABLE);
        }
    }

    private static List<ConversationMessage> assemble(ContextWindow window,
            List<ContextFactService.ContextFactSnippet> facts, ContextOperationalMaterials operational,
            ConversationEvidenceResult evidence, ContextAllocationPlan plan) {
        ContextDynamicBudget budget = plan.dynamicBudget();
        int nonEvidenceBudget = Math.max(1, budget.verifiedFactsTokens() + budget.summaryTokens()
                + budget.recentHistoryTokens());
        ContextBudget historyBudget = new ContextBudget(0, 0, 0, 0, 0)
                .with(ContextBudget.CallSite.CHAT, nonEvidenceBudget);
        List<ConversationMessage> assembled = new ArrayList<>(
                ContextAssembler.chatHistory(window, historyBudget, facts, operational));
        // 证据位于近期会话之前，且检索器已经在独立配额内加上不可信数据边界。
        evidence.contextMessage().ifPresent(message -> assembled.add(0, message));
        return List.copyOf(assembled);
    }

    private static int estimateToolSchemas(AgentTool[] tools) {
        if (tools == null || tools.length == 0) {
            return 0;
        }
        int total = 0;
        for (AgentTool tool : tools) {
            if (tool == null) {
                continue;
            }
            total += TokenEstimator.estimate(tool.getName());
            total += TokenEstimator.estimate(tool.getDescription());
            total += TokenEstimator.estimate(String.valueOf(tool.getParameters()));
            total += TokenEstimator.estimate(String.valueOf(tool.getOutputSchema()));
        }
        return total;
    }

    private static int estimateMessages(List<ConversationMessage> messages) {
        return messages == null ? 0 : ContextTrimmer.estimateTokens(messages);
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private int longContextMaxMessages() {
        return chatProperties == null || chatProperties.memory() == null
                ? ChatProperties.Memory.DEFAULT_LONG_CONTEXT_MAX_MESSAGES
                : chatProperties.memory().longContextMaxMessages();
    }

    private static ContextWindow emptyWindow() {
        return new ContextWindow(null, com.example.vatica.controller.SessionSummaryStatus.PENDING,
                0, 0, 0, List.of(), List.of(), List.of(), 0, 0);
    }

    private static String sessionIdOf(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    public record PreparationRequest(String sessionId, ContextMode contextMode,
            ModelCapabilityProfile modelCapability, String systemPrompt, String userPrompt, AgentTool[] tools,
            String taskId) {
        public PreparationRequest {
            contextMode = ContextMode.normalize(contextMode);
            modelCapability = modelCapability == null ? ModelCapabilityProfile.unknown("") : modelCapability;
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
            userPrompt = userPrompt == null ? "" : userPrompt;
            tools = tools == null ? new AgentTool[0] : tools.clone();
            taskId = taskId == null || taskId.isBlank() ? null : taskId.trim();
        }

        public PreparationRequest(String sessionId, ContextMode contextMode,
                ModelCapabilityProfile modelCapability, String systemPrompt, String userPrompt, AgentTool[] tools) {
            this(sessionId, contextMode, modelCapability, systemPrompt, userPrompt, tools, null);
        }

        public static PreparationRequest empty() {
            return new PreparationRequest("default", ContextMode.NORMAL, ModelCapabilityProfile.unknown(""),
                    "", "", new AgentTool[0], null);
        }

        @Override
        public AgentTool[] tools() {
            return tools.clone();
        }
    }

    public record PreparedChatContext(List<ConversationMessage> history, ContextAllocationPlan plan,
            ChatContextStatus status) {
        public PreparedChatContext {
            history = history == null ? List.of() : List.copyOf(history);
            if (plan == null) {
                throw new IllegalArgumentException("上下文计划不能为空");
            }
            if (status == null) {
                throw new IllegalArgumentException("上下文状态不能为空");
            }
        }
    }

    private record ReadWindow(ContextWindow window, String status) {
        private ReadWindow {
            window = window == null ? emptyWindow() : window;
            status = status == null || status.isBlank() ? "UNAVAILABLE" : status;
        }
    }

    private record FactRead(List<ContextFactService.ContextFactSnippet> snippets, boolean failed) {
        private FactRead {
            snippets = snippets == null ? List.of() : List.copyOf(snippets);
        }

        private static FactRead empty() {
            return new FactRead(List.of(), false);
        }
    }
}
