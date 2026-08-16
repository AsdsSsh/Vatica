package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.PermissionEventPublisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/**
 * 对话控制器单测（迭代 2.5 I2.5-1/I2.5-3）：SSE 错误传播 / 超时 / 断连清理 + 会话历史回放；
 * 迭代 7 I7-5 增补：模型路由（未配置/未知模型快速失败）；
 * 迭代 8.5：路由改为动态模型注册表（mock 注册表，验证分派与快速失败）。
 *
 * <p>这里测的是迭代 2.5 之后控制器里"真实存在"的错误处理逻辑（错误路径而非转发），
 * 是规划文档 7.2"胶水层不测"规则的例外；LLM 行为仍 mock 化。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerTest {

    @BeforeEach
    void setIdentity() {
        RequestIdentityContext.set(new RequestIdentity(1L, 1L, "LOCAL", "test"));
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Mock
    ModelRegistry registry;
    @Mock
    ChatClient chatClient;
    @Mock
    ChatClient qwenChatClient;
    @Mock
    ChatClient.ChatClientRequestSpec spec;
    @Mock
    ChatClient.ChatClientRequestSpec qwenSpec;
    @Mock
    ChatClient.StreamResponseSpec streamSpec;
    @Mock
    ChatClient.CallResponseSpec callSpec;
    @Mock
    ToolCallbackProvider toolProvider;
    @Mock
    PermissionEventPublisher permissionEvents;
    @Mock
    FilePermissionRequestService permissionRequests;

    @BeforeEach
    void stubCommonChain() {
        // 默认路由：任何模型都回落到主客户端（具体测试按需覆盖 registry 行为）
        when(registry.defaultClient()).thenReturn(chatClient);
        when(registry.clientFor(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(toolProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
    }

    private ChatProperties defaultProps() {
        return new ChatProperties(
                new ChatProperties.Sse(Duration.ofMinutes(5)),
                new ChatProperties.Memory(20, 64, 16000));
    }

    private ChatController newController(ChatProperties props, SessionMemory memory) {
        // 迭代 8.5：控制器注入动态模型注册表（客户端按请求解析）；迭代 11 注入权限组件
        return new ChatController(registry, props, memory, toolProvider, permissionEvents, permissionRequests);
    }

    private MockMvc mockMvcFor(ChatController controller) {
        // forceEncoding=true：请求与响应统一 UTF-8（否则 MockHttpServletResponse 按 ISO-8859-1
        // 写字节，中文在写入阶段就被替换为 '?'，且无从在断言侧还原）
        CharacterEncodingFilter filter = new CharacterEncodingFilter(java.nio.charset.StandardCharsets.UTF_8.name(), true);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    /** 正常流式：chunk 逐段送达，连接正常收尾，注册表清空，一轮对话入记忆。 */
    @Test
    void streamDeliversChunksAndCleansUp() throws Exception {
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("你", "好", "！"));
        SessionMemory memory = new InMemorySessionMemory(20, 64, 16000);
        ChatController controller = newController(defaultProps(), memory);
        MockMvc mockMvc = mockMvcFor(controller);

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"sessionId\":\"s1\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 注意：MockMvc 响应默认按 ISO-8859-1 解码，中文断言须显式用 UTF-8 读字节
        // （真实 SSE 按规范 text/event-stream 即 UTF-8，浏览器解码正常）
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(body).contains("你").contains("好").contains("！");
                });

        assertThat(controller.activeStreamCount()).isZero();
        List<Message> history = memory.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(history.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(history.get(1).getText()).isEqualTo("你好！");
    }

    /** 上游异常：2 秒内完成收尾（不挂起），客户端拿到异常，注册表清空。 */
    @Test
    void upstreamErrorCompletesWithoutHanging() throws Exception {
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(
                Flux.<String>error(new RuntimeException("上游 API 超时"))
                        .delaySubscription(Duration.ofMillis(100)));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 核心断言：异步结果在限时内到达（修复前会永久挂起）
        Object asyncResult = result.getAsyncResult(2000);
        assertThat(asyncResult).isInstanceOf(RuntimeException.class);
        assertThat(controller.activeStreamCount()).isZero();
    }

    /** SSE 超时配置生效：emitter 超时 = vatica.chat.sse.timeout。
     *  <p>真实"超时主动收尾"依赖容器调度（MockMvc 的 MockAsyncContext 不模拟超时），
     *  由 I2.5-1 的 curl 集成验证覆盖（base-url 指向不可达地址 + 短超时观察连接收尾）。 */
    @Test
    void sseTimeoutIsConfigured() {
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.never());
        ChatProperties props = new ChatProperties(
                new ChatProperties.Sse(Duration.ofSeconds(30)),
                new ChatProperties.Memory(20, 64, 16000));
        ChatController controller = newController(props, new InMemorySessionMemory(20, 64, 16000));

        SseEmitter emitter = controller.stream(new ChatRequest("你好", null, null, null));

        assertThat(emitter.getTimeout()).isEqualTo(30_000L);
        assertThat(controller.activeStreamCount()).isEqualTo(1); // 挂起期间计入活跃连接
    }

    /** 迭代 7 I7-5 / 迭代 8.5：注册表拒绝的模型（未启用/未知）→ 快速失败，不进入流式流程。 */
    @Test
    void modelRoutingRejectsUnconfiguredOrUnknownModel() {
        when(registry.clientFor("qwen")).thenThrow(
                new IllegalArgumentException("操作失败：模型未启用（通义千问），请在设置中启用。"));
        when(registry.clientFor("gpt-5")).thenThrow(
                new IllegalArgumentException("操作失败：未知模型（gpt-5）。"));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.stream(new ChatRequest("你好", null, "qwen", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未启用");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> controller.stream(new ChatRequest("你好", null, "gpt-5", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知模型");
    }

    /** 迭代 7 I7-5 / 迭代 8.5：显式模型 id 路由到注册表对应客户端。 */
    @Test
    void modelRoutingUsesSelectedClient() throws Exception {
        when(registry.clientFor("qwen")).thenReturn(qwenChatClient);
        when(qwenChatClient.prompt()).thenReturn(qwenSpec);
        when(qwenSpec.system(anyString())).thenReturn(qwenSpec);
        when(qwenSpec.messages(anyList())).thenReturn(qwenSpec);
        when(qwenSpec.user(anyString())).thenReturn(qwenSpec);
        when(qwenSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(qwenSpec);
        when(qwenSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("通义回复");
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"model\":\"qwen\"}"))
                .andExpect(status().isOk());

        verify(qwenChatClient).prompt();
    }

    /** 迭代 13 I13-5：请求级临时凭据路由到 ephemeralClient，不与共享缓存混用。 */
    @Test
    void ephemeralCredentialRoutesToRequestClient() throws Exception {
        when(registry.ephemeralClient(any(), anyBoolean())).thenReturn(qwenChatClient);
        when(qwenChatClient.prompt()).thenReturn(qwenSpec);
        when(qwenSpec.system(anyString())).thenReturn(qwenSpec);
        when(qwenSpec.messages(anyList())).thenReturn(qwenSpec);
        when(qwenSpec.user(anyString())).thenReturn(qwenSpec);
        when(qwenSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(qwenSpec);
        when(qwenSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("临时回复");
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"credential\":{\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://api.deepseek.com\",\"model\":\"deepseek-v4-flash\","
                                + "\"temperature\":0.7,\"apiKey\":\"sk-ephemeral\"}}"))
                .andExpect(status().isOk());

        verify(registry).ephemeralClient(any(), eq(true));
    }

    /** 迭代 13 I13-5：临时凭据与 modelId 同时出现 → 400，避免路由歧义。 */
    @Test
    void ephemeralCredentialWithModelIdRejected() {
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.chat(new ChatRequest(
                "你好", null, "deepseek", null,
                new com.example.vatica.config.EphemeralCredential("openai", "https://api.deepseek.com",
                        "deepseek-v4-flash", 0.7, "sk-x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能同时使用");
    }

    /** 迭代 8.5：模型清单来自动态注册表（configured = 槽位启用开关）。 */
    @Test
    void modelsListComesFromRegistry() throws Exception {
        when(registry.slots()).thenReturn(List.of(
                new ModelSlot("deepseek", "DeepSeek v4", "openai", "https://api.deepseek.com",
                        "k", "deepseek-v4-flash", 0.7, true),
                new ModelSlot("claude", "Claude", "anthropic", "https://api.anthropic.com",
                        "", "claude-sonnet-4-6", 0.7, false)));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(get("/api/chat/models"))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(body)
                            .contains("\"id\":\"deepseek\"")
                            .contains("\"configured\":true")
                            .contains("\"id\":\"claude\"")
                            .contains("\"configured\":false");
                });
    }

    /** 非流式：第二轮请求自动带上第一轮历史（user + assistant）。 */
    @Test
    void chatAppendsAndReplaysHistory() throws Exception {
        when(callSpec.content()).thenReturn("好的，已记录");
        when(spec.call()).thenReturn(callSpec);
        SessionMemory memory = new InMemorySessionMemory(20, 64, 16000);
        ChatController controller = newController(defaultProps(), memory);
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"记住：我叫小明\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"我叫什么\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(spec, atLeast(2)).messages(captor.capture());
        List<Message> replay = captor.getValue();
        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(replay.get(0).getText()).contains("记住");
        assertThat(replay.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
    }
}
