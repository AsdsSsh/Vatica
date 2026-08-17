package com.example.vatica.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ReasoningMode;
import com.example.vatica.context.ContextAssembler;
import com.example.vatica.context.ContextBudget;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.auth.TenantChannels;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.permission.PermissionEventPublisher;
import com.example.vatica.trace.TraceContext;
import com.example.vatica.trace.TracedToolCallbacks;
import com.example.vatica.tool.RetryableToolCallbacks;
import com.example.vatica.usage.UsageContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

/**
 * 对话接口 —— 迭代 1：能聊天的后端；迭代 2.5：SSE 健壮性 + 会话短期记忆（内存版）；
 * 迭代 7：模型选择器（按请求 model 字段路由）；迭代 8.5：路由改为动态模型注册表
 * （界面配置中心，默认模型 = 第一个启用的槽位）。
 *
 * <p>流式错误处理约定（迭代 2.5 代码审查 R1 修复）：
 * <ul>
 *   <li>上游异常（API 超时/断网）→ {@code completeWithError} 通知客户端，绝不让连接无限挂起</li>
 *   <li>客户端断连（send 抛 IOException）→ 取消上游订阅、从注册表清理，避免连接与 token 泄漏</li>
 *   <li>SSE 超时（vatica.chat.sse.timeout，默认 5 分钟）→ 取消订阅并正常结束连接</li>
 * </ul>
 *
 * <p>会话记忆：每轮对话（user / 最终 assistant 纯文本）写入 {@link SessionMemory}，
 * 下一轮请求自动带上前文（滑动窗口；迭代 5 起 JPA 落库，重启不丢）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 迭代 12 热修：聊天系统提示——让模型理解"越界路径会自动触发用户授权弹窗"。 */
    private static final String SYSTEM_PROMPT = """
            你是 Vatica 个人 AI 助理，可以使用文件、日历、待办、邮件、文档等工具完成用户请求。
            文件权限规则（非常重要）：
            1. 用户要求读取/写入/列出任何具体路径时，直接调用对应文件工具（read_file / list_files / write_file 等），
               不要因为路径不在当前工作区根就拒绝，也不要要求用户先去设置页手动添加目录；
            2. 访问未授权目录会自动触发系统权限弹窗，由用户选择允许或拒绝，等待期间不要重复调用该工具；
            3. 只有工具明确返回"用户拒绝授权"时，才向用户说明被拒绝的原因，并建议换一个路径或让用户重新发起操作；
            4. 你没有任何"添加授权目录"的工具，也永远不要指导用户去"文件权限设置"里手动添加目录。
            数据铁律：只使用工具返回的数据，不得编造工具没有返回的内容。""";

    private final ModelRegistry registry;
    private final ChatProperties chatProperties;
    private final SessionMemory sessionMemory;
    private final ToolCallbackProvider vaticaTools;
    private final PermissionEventPublisher permissionEvents;
    private final FilePermissionRequestService permissionRequests;
    private final ObjectMapper mapper;
    private final ContextBudget contextBudget;

    /** 活跃流式连接注册表：可观测 + 断连清理（迭代 5 任务终止/迭代 7 前端联调可复用）。 */
    private final Set<SseEmitter> activeEmitters = ConcurrentHashMap.newKeySet();

    public ChatController(ModelRegistry registry, ChatProperties chatProperties, SessionMemory sessionMemory,
            ToolCallbackProvider vaticaTools, PermissionEventPublisher permissionEvents,
            FilePermissionRequestService permissionRequests, ObjectMapper mapper, ContextBudget contextBudget) {
        this.registry = registry;
        this.chatProperties = chatProperties;
        this.sessionMemory = sessionMemory;
        this.vaticaTools = vaticaTools;
        this.permissionEvents = permissionEvents;
        this.permissionRequests = permissionRequests;
        this.mapper = mapper;
        this.contextBudget = contextBudget;
    }

    /** 可用模型清单（迭代 7 模型选择器；迭代 8.5 起来自动态注册表；迭代 9 类型化 DTO）。 */
    @GetMapping("/models")
    public List<ModelInfoDto> models() {
        return registry.slots().stream()
                .map(s -> new ModelInfoDto(s.id(), s.name(), s.enabled()))
                .toList();
    }

    /** 按请求路由模型（迭代 8.5：id 空取默认；未启用/未知模型快速失败，不进入流式流程）。 */
    private ChatClient resolveClient(String model) {
        if (model == null || model.isBlank()) {
            return registry.defaultClient();
        }
        if (model.startsWith("user:")) {
            RequestIdentity identity = RequestIdentityContext.require();
            return registry.userClient(identity.userId(), model.substring("user:".length()), true);
        }
        return registry.clientFor(model);
    }

    /** 迭代 13 I13-5：有 credential 走请求级临时客户端；两者同时出现快速失败。 */
    private ChatClient resolveClient(ChatRequest request) {
        if (request.credential() != null) {
            if (request.model() != null && !request.model().isBlank()) {
                throw new IllegalArgumentException("操作失败：临时凭据与 modelId 不能同时使用。");
            }
            return registry.ephemeralClient(request.credential(), true);
        }
        return resolveClient(request.model());
    }

    /** 非流式对话（无 UI 权限弹窗：越界直接拒绝，由前端把权限快照先行送达） */
    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        ChatClient client = resolveClient(request);
        ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                vaticaTools, request.permission(), null, identity, request.mailCredential());
        // 迭代 15 I15-3：retryable 工具错误带上下文重试 1 次（权限包装在内层，每次真实执行都过权限）
        callbacks = new RetryableToolCallbacks().wrap(callbacks);
        // 迭代 15 I15-1：显式 ReAct trace（非流式仅脱敏与计时，不推送 SSE）
        callbacks = new TracedToolCallbacks(mapper, (SseEmitter) null).wrap(callbacks, chatTrace(identity, request.sessionId()));
        var prompt = client.prompt()
                .system(SYSTEM_PROMPT)
                .messages(ContextAssembler.chatHistory(sessionMemory, request.sessionId(), contextBudget))
                .user(request.message())
                .toolCallbacks(callbacks);
        UsageContext.set(usageSnapshot(request, identity));
        try {
            String reply = withDeepThinking(prompt, request, identity).call().content();
            sessionMemory.append(request.sessionId(), request.message(), reply);
            return reply;
        } finally {
            UsageContext.clear();
        }
    }

    /** SSE 流式对话（打字机效果；迭代 11 增加权限请求事件） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        RequestIdentity identity = RequestIdentityContext.require();
        ChatClient client = resolveClient(request);
        SseEmitter emitter = new SseEmitter(chatProperties.sse().timeout().toMillis());
        activeEmitters.add(emitter);
        String channel = TenantChannels.chat(identity, request.sessionId());
        permissionEvents.subscribe(channel, emitter);
        // 迭代 15 I15-13：本次流式请求的用量上下文（advisor 读取，收尾发 usage 事件）
        UsageContext.set(usageSnapshot(request, identity));

        ToolCallback[] callbacks = PermissionBoundToolCallbacks.wrap(
                vaticaTools, request.permission(), channel, identity, request.mailCredential());
        // 迭代 15 I15-3：Self-Refine 重试（权限最内层，重试也重新过权限与身份快照）
        callbacks = new RetryableToolCallbacks().wrap(callbacks);
        // 迭代 15 I15-1：升级迭代 12 的 tool_activity——补 traceId 与脱敏输入/输出摘要
        callbacks = new TracedToolCallbacks(mapper, emitter).wrap(callbacks, chatTrace(identity, request.sessionId()));

        StringBuilder reply = new StringBuilder();
        Disposable[] subscription = new Disposable[1];
        Runnable cleanup = () -> {
            if (subscription[0] != null) {
                subscription[0].dispose();
            }
            activeEmitters.remove(emitter);
            permissionEvents.unsubscribe(channel, emitter);
            permissionRequests.cancelChannel(channel);
            UsageContext.clear();
            log.debug("SSE 流式收尾：session={}，剩余活跃连接={}", request.sessionId(), activeEmitters.size());
        };

        // 先注册收尾回调再订阅：上游若同步完成（如单测 Flux.just / 极快响应），
        // 回调也要能兜住收尾路径，不依赖回调注册时序
        emitter.onTimeout(cleanup::run);
        emitter.onError(e -> cleanup.run());
        emitter.onCompletion(cleanup::run);

        subscription[0] = withDeepThinking(client.prompt()
                .system(SYSTEM_PROMPT)
                .messages(ContextAssembler.chatHistory(sessionMemory, request.sessionId(), contextBudget))
                .user(request.message())
                .toolCallbacks(callbacks), request, identity)
                .stream()
                .chatResponse()
                .subscribe(
                        response -> {
                            // 迭代 15 I15-7：reasoning_content 走独立 SSE 事件，文本照旧逐块输出
                            String reasoning = reasoningContent(response);
                            if (reasoning != null && !reasoning.isBlank()) {
                                try {
                                    emitter.send(SseEmitter.event().name("reasoning")
                                            .data(mapper.writeValueAsString(Map.of("content", reasoning))));
                                } catch (IOException e) {
                                    cleanup.run();
                                    emitter.completeWithError(e);
                                    return;
                                }
                            }
                            String chunk = response.getResult() == null
                                    || response.getResult().getOutput() == null
                                    ? "" : response.getResult().getOutput().getText();
                            if (chunk == null || chunk.isEmpty()) {
                                return;
                            }
                            reply.append(chunk);
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                // 客户端已断连：停止上游、结束会话
                                cleanup.run();
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            cleanup.run();
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // 迭代 15 I15-13：收尾聚合 usage 事件（advisor 已写入 ThreadLocal）
                            String usageJson = UsageContext.takeLastUsageJson();
                            if (usageJson != null) {
                                try {
                                    emitter.send(SseEmitter.event().name("usage").data(usageJson));
                                } catch (IOException e) {
                                    // 客户端断连：下方 cleanup 统一收尾
                                }
                            }
                            RequestIdentityContext.callWith(identity, () -> {
                                sessionMemory.append(request.sessionId(), request.message(), reply.toString());
                                return null;
                            });
                            cleanup.run();
                            emitter.complete();
                        });

        return emitter;
    }

    /** 迭代 15 I15-7：从 ChatResponse 提取思考内容（优先消息级，其次响应级；不存在返回 null）。 */
    private static String reasoningContent(org.springframework.ai.chat.model.ChatResponse response) {
        for (org.springframework.ai.chat.model.Generation generation : response.getResults()) {
            if (generation.getOutput() != null) {
                Object value = generation.getOutput().getMetadata().get("reasoningContent");
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        if (response.getMetadata() != null) {
            Object value = response.getMetadata().get("reasoningContent");
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** 迭代 15 I15-4：用户开启“深思”时，按当前模型槽位协议注入 HIGH 思考选项。 */
    private ChatClient.ChatClientRequestSpec withDeepThinking(ChatClient.ChatClientRequestSpec spec,
            ChatRequest request, RequestIdentity identity) {
        if (!Boolean.TRUE.equals(request.deepThinking())) {
            return spec;
        }
        if (request.credential() != null) {
            return spec.options(registry.reasoningOptions(null, request.credential(), ReasoningMode.HIGH));
        }
        if (request.model() != null && request.model().startsWith("user:")) {
            return spec.options(registry.reasoningOptionsForUser(identity.userId(),
                    request.model().substring("user:".length()), ReasoningMode.HIGH));
        }
        return spec.options(registry.reasoningOptions(request.model(), null, ReasoningMode.HIGH));
    }

    /** 迭代 15 I15-13：聊天用量上下文——平台模型计配额，自配/临时模型只记录不扣额度。 */
    private UsageContext.Snapshot usageSnapshot(ChatRequest request, RequestIdentity identity) {
        boolean platformQuota = request.credential() == null
                && (request.model() == null || !request.model().startsWith("user:"));
        String slotId = request.credential() != null ? "ephemeral"
                : request.model() == null || request.model().isBlank() ? "default" : request.model();
        return new UsageContext.Snapshot(UsageContext.newRequestId(), "CHAT", identity.userId(),
                identity.orgId(), slotId, null, null,
                Boolean.TRUE.equals(request.deepThinking()) ? "HIGH" : "DISABLED",
                contextBudget.chatTokens(), null, platformQuota);
    }

    /** 聊天 trace 快照：只走 SSE（persist=false），channel 用于迭代 16 统一事件网关前定位会话。 */
    private static TraceContext.Snapshot chatTrace(RequestIdentity identity, String sessionId) {
        return new TraceContext.Snapshot(UUID.randomUUID().toString(),
                TenantChannels.chat(identity, sessionId), null, null,
                identity.userId(), identity.orgId(), false);
    }

    /** 当前活跃流式连接数（可观测性 / 单测验证断连清理）。 */
    int activeStreamCount() {
        return activeEmitters.size();
    }
}
