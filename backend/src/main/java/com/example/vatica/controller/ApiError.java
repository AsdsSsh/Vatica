package com.example.vatica.controller;

/**
 * 统一错误响应体（迭代 9 I9-3，前后端分离契约的一部分）：
 * 后端所有非 2xx 响应均为 {@code {"message": "<用户可读原因>"}}，
 * 前端 api.ts 统一解析该结构并透出服务端消息。
 *
 * @param message 用户可读的错误原因（不泄漏堆栈/内部细节）
 */
public record ApiError(String message) {
}
