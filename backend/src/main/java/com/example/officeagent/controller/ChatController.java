package com.example.officeagent.controller;

import java.io.IOException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口 —— 第 1 周里程碑：能聊天的后端。
 *
 * <p>包结构按最终架构分层（tool/agent/task 目录已预建，后续周次填入内容）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 非流式对话 */
    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        return chatClient.prompt(request.message()).call().content();
    }

    /** SSE 流式对话（打字机效果） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L); // 0L = 不自动超时
        chatClient.prompt(request.message())
                .stream()
                .content()
                .doOnComplete(emitter::complete)
                .subscribe(content -> {
                    try {
                        emitter.send(content);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
        return emitter;
    }
}
