package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.example.vatica.task.TaskNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局错误处理单测（迭代 9 I9-3）：错误响应统一 {@code {"message": ...}} 契约——
 * 400 业务校验 / 404 资源不存在 / 500 根因消息（最内层、不泄漏堆栈）。
 */
class GlobalExceptionHandlerTest {

    /** 探针控制器：抛各类异常验证映射（真实控制器不抛的路径也锁进契约）。 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/validation")
        String validation() {
            throw new IllegalArgumentException("操作失败：任务目标不能为空。");
        }

        @GetMapping("/probe/not-found")
        String notFound() {
            throw new TaskNotFoundException("abc");
        }

        @GetMapping("/probe/state")
        String state() {
            throw new IllegalStateException("外层包装", new RuntimeException("401 Unauthorized"));
        }

        @GetMapping("/probe/generic")
        String generic() {
            throw new NullPointerException("内部细节");
        }

        /** 未知路径在真实容器里由资源处理器抛 NoResourceFoundException（纯 API 后端无静态资源兜底）。 */
        @GetMapping("/probe/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/no-such-page", "/**");
        }
        @PostMapping("/probe/body")
        String body(@RequestBody java.util.Map<String, Object> body) {
            return "ok";
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 业务校验失败 → 400 + 用户可读消息。 */
    @Test
    void validationErrorMapsTo400WithMessage() throws Exception {
        mockMvc.perform(get("/probe/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).isEqualTo("{\"message\":\"操作失败：任务目标不能为空。\"}");
                });
    }

    /** 任务不存在（IllegalArgumentException 子类）→ 404（更具体类型分支优先于 400）。 */
    @Test
    void notFoundMapsTo404WithMessage() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).isEqualTo("{\"message\":\"操作失败：任务不存在（id=abc）。\"}");
                });
    }

    /** 内部状态错误 → 500 + 根因消息（剥到最内层）。 */
    @Test
    void stateErrorMapsTo500WithRootMessage() throws Exception {
        mockMvc.perform(get("/probe/state"))
                .andExpect(status().isInternalServerError())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).isEqualTo("{\"message\":\"401 Unauthorized\"}");
                });
    }

    /** 未预期异常 → 500 + 根因消息（不泄漏内部堆栈细节）。 */
    @Test
    void genericErrorMapsTo500WithRootMessage() throws Exception {
        mockMvc.perform(get("/probe/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).isEqualTo("{\"message\":\"内部细节\"}");
                });
    }

    /** 请求体不可读（非法 JSON）→ 400 + 统一消息。 */
    @Test
    void unreadableBodyMapsTo400() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).contains("\"message\"");
                });
    }

    /** 未知路径（无静态资源兜底后的资源缺失）→ 404 + 统一消息。 */
    @Test
    void noResourceMapsTo404WithMessage() throws Exception {
        mockMvc.perform(get("/probe/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertThat(body).isEqualTo("{\"message\":\"接口不存在：/probe/no-resource\"}");
                });
    }
}
