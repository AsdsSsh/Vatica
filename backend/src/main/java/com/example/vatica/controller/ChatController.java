package com.example.vatica.controller;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.vatica.config.ChatProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

/**
 * 对话接口 —— 迭代 1：能聊天的后端；迭代 2.5：SSE 健壮性 + 会话短期记忆（内存版）。
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
    private final ChatProperties chatProperties;
    private final SessionMemory sessionMemory;

    /** 活跃流式连接注册表：可观测 + 断连清理（迭代 5 任务终止/迭代 7 前端联调可复用）。 */
    private final Set<SseEmitter> activeEmitters = ConcurrentHashMap.newKeySet();

    public ChatController(ChatClient.Builder builder, ToolCallbackProvider vaticaTools,
            ChatProperties chatProperties, SessionMemory sessionMemory) {
        this.chatClient = builder.defaultTools(vaticaTools).build();
        this.chatProperties = chatProperties;
        this.sessionMemory = sessionMemory;
    }

    /** 非流式对话 */
    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        String reply = chatClient.prompt()
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

        subscription[0] = chatClient.prompt()
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
