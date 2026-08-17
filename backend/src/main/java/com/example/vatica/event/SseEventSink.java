package com.example.vatica.event;

/** 统一 SSE 事件写入口（迭代 16 I16-2/I16-4）。 */
@FunctionalInterface
public interface SseEventSink {

    /**
     * 发布事件。
     *
     * @return 至少一个当前订阅者成功收到事件
     */
    boolean emit(String type, Object data);
}
