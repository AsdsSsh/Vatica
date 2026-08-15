package com.example.vatica.controller;

import com.example.vatica.task.TaskNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局错误处理（迭代 9 I9-3）：把分散在各控制器里的错误响应收敛到一处，
 * 统一结构 {@code {"message": "<用户可读原因>"}}——前端透出服务端消息、OpenAPI 契约唯一形态。
 *
 * <p>状态码语义：
 * <ul>
 *   <li>{@code 400}：业务校验失败（空目标 / 非法状态流转 / 未知模型 / 请求体不可读）</li>
 *   <li>{@code 404}：资源不存在（任务 id 不存在——继承自 IllegalArgumentException，
 *       声明更具体类型的分支处理器后按资源语义映射）</li>
 *   <li>{@code 500}：服务器内部错误（根因消息透出，不泄漏堆栈）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 资源不存在（任务 id 查无）→ 404。 */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(e.getMessage()));
    }

    /** 无匹配资源（未知路径——纯 API 后端无静态资源兜底，删 static 后由资源处理器抛出）→ 404。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e, jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("接口不存在：" + request.getRequestURI()));
    }

    /** 请求体不可读（JSON 格式错误等）→ 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new ApiError("请求体格式错误：不是合法的 JSON。"));
    }

    /** 业务校验失败 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleValidation(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(e.getMessage() == null ? "参数不合法" : e.getMessage()));
    }

    /** 存储失败等内部状态错误 → 500（根因消息）。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleState(IllegalStateException e) {
        log.error("服务内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiErrors.rootMessage(e)));
    }

    /** 兜底：其余一切异常 → 500（根因消息，不泄漏堆栈）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiErrors.rootMessage(e)));
    }
}
