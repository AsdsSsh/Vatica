package com.example.vatica.controller;

/**
 * 对话请求体。
 *
 * @param message   用户消息内容
 * @param sessionId 可选会话 ID；为空时使用默认会话（迭代 2.5 会话短期记忆引入）
 */
public record ChatRequest(String message, String sessionId) {
}
