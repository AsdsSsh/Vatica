package com.example.vatica.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 迭代 7：模型选择器（deepseek 主 / qwen 备，按请求 model 字段路由）。
 *
 * <p>流式错误处理约定（迭代 2.5 代码审查 R1 修复）：
 * <ul>
 *   <li>上游异常（API 超时/断网）→ {@code completeWithError} 通知客户端，绝不让连接无限挂起</li>
 *   <li>客户端断连（send 抛 IOException）→ 取消上游订阅、从注册表清理，避免连接与 token 泄漏</li>
 *   <li>SSE 超时（vatica.chat.sse.timeout，默认 5 分钟）→ 取消订阅并正常结束连接</li>
 * </ul>
 *
 * <p>会话记忆：每轮对话（user / 最终 assistant 纯文本）写入 {@link SessionMemory}，
 * 下一轮请求自动带上前文（滑动窗口，内存版不持久化——持久化仍在迭代 5）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final ChatClient qwenChatClient;
    private final ModelProperties modelProperties;
    private final ChatProperties chatProperties;
    private final SessionMemory sessionMemory;

    /** 活跃流式连接注册表：可观测 + 断连清理（迭代 5 任务终止/迭代 7 前端联调可复用）。 */
    private final Set<SseEmitter> activeEmitters = ConcurrentHashMap.newKeySet();

    public ChatController(@Qualifier("vaticaChatClient") ChatClient chatClient,
            @Qualifier("qwenChatClient") ChatClient qwenChatClient,
            ModelProperties modelProperties, ChatProperties chatProperties, SessionMemory sessionMemory) {
        // 迭代 4：MCP 远程工具与本地工具的合并已上移到 ChatConfig（vaticaChatClient Bean）；
        // 迭代 5：会话记忆注入接口（内存实现用于单测，JPA 实现用于生产）
        this.chatClient = chatClient;
        this.qwenChatClient = qwenChatClient;
        this.modelProperties = modelProperties;
        this.chatProperties = chatProperties;
        this.sessionMemory = sessionMemory;
    }

    /** 可用模型清单（迭代 7 模型选择器数据源）。 */
    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return List.of(
                Map.of("id", "deepseek", "name", "DeepSeek deepseek-v4-flash", "configured", true),
                Map.of("id", "qwen", "name", "通义千问 qwen-plus",
                        "configured", modelProperties.qwen().configured()));
    }

    /** 按请求路由模型（未知/未配置模型快速失败，不进入流式流程）。 */
    private ChatClient resolveClient(String model) {
        if (model == null || model.isBlank() || model.equalsIgnoreCase("deepseek")) {
            return chatClient;
        }
        if (model.equalsIgnoreCase("qwen")) {
            if (!modelProperties.qwen().configured()) {
                throw new IllegalArgumentException(
                        "操作失败：通义千问未配置（设置 QWEN_API_KEY 环境变量后重启）。");
            }
            return qwenChatClient;
        }
        throw new IllegalArgumentException("操作失败：未知模型（" + model + "）。");
    }

    /** 非流式对话 */
    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        ChatClient client = resolveClient(request.model());
        String reply = client.prompt()
                .messages(sessionMemory.history(request.sessionId()))
                .user(request.message())
                .call()
                .content();
        sessionMemory.append(request.sessionId(), request.message(), reply);
        return reply;
    }

    /** SSE 流式对话（打字机效果） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        ChatClient client = resolveClient(request.model());
        SseEmitter emitter = new SseEmitter(chatProperties.sse().timeout().toMillis());
        activeEmitters.add(emitter);

        StringBuilder reply = new StringBuilder();
        Disposable[] subscription = new Disposable[1];
        Runnable cleanup = () -> {
            if (subscription[0] != null) {
                subscription[0].dispose();
            }
            activeEmitters.remove(emitter);
            log.debug("SSE 流式收尾：session={}，剩余活跃连接={}", request.sessionId(), activeEmitters.size());
        };

        // 先注册收尾回调再订阅：上游若同步完成（如单测 Flux.just / 极快响应），
        // 回调也要能兜住收尾路径，不依赖回调注册时序
        emitter.onTimeout(cleanup::run);
        emitter.onError(e -> cleanup.run());
        emitter.onCompletion(cleanup::run);

        subscription[0] = client.prompt()
                .messages(sessionMemory.history(request.sessionId()))
                .user(request.message())
                .stream()
                .content()
                .subscribe(
                        chunk -> {
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
                            sessionMemory.append(request.sessionId(), request.message(), reply.toString());
                            cleanup.run();
                            emitter.complete();
                        });

        return emitter;
    }

    /** 当前活跃流式连接数（可观测性 / 单测验证断连清理）。 */
    int activeStreamCount() {
        return activeEmitters.size();
    }
}
