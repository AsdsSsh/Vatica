package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.example.vatica.agentscope.AgentScopeChatService;
import com.example.vatica.agentscope.AgentScopeChatService.ChatEvent;
import com.example.vatica.agentscope.AgentScopeChatService.ChatResult;
import com.example.vatica.config.ChatProperties;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.model.ConversationMessage;
import com.example.vatica.model.ModelUsage;
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
    AgentScopeChatService chatService;
    @Mock
    ToolCallbackProvider toolProvider;
    @Mock
    PermissionEventPublisher permissionEvents;
    @Mock
    FilePermissionRequestService permissionRequests;

    @BeforeEach
    void stubCommonChain() {
        ModelSlot defaultSlot = slot("deepseek", true);
        when(registry.defaultSlot()).thenReturn(defaultSlot);
        when(registry.slotFor(anyString())).thenReturn(defaultSlot);
        when(toolProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(chatService.call(any())).thenReturn(new ChatResult("好的，已记录", "", ModelUsage.empty()));
        when(chatService.stream(any())).thenReturn(Flux.just(
                new ChatEvent(ChatEvent.Type.TEXT, "好的", null),
                new ChatEvent(ChatEvent.Type.USAGE, null, ModelUsage.empty())));
    }

    private ChatProperties defaultProps() {
        return new ChatProperties(
                new ChatProperties.Sse(Duration.ofMinutes(5)),
                new ChatProperties.Memory(20, 64, 16000));
    }

    private static ModelSlot slot(String id, boolean enabled) {
        return new ModelSlot(id, id, "openai", "https://example.test", "k", id + "-model", 0.7, enabled);
    }

    private ChatController newController(ChatProperties props, SessionMemory memory) {
        // 迭代 8.5：控制器注入动态模型注册表（客户端按请求解析）；迭代 11 注入权限组件
        // 迭代 15：trace 包装需要 ObjectMapper（脱敏摘要序列化）+ ContextBudget 组装三层记忆
        return new ChatController(registry, props, memory, toolProvider, permissionEvents, permissionRequests,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.example.vatica.context.ContextBudget(0, 0, 0, 0, 0), chatService);
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
        when(chatService.stream(any())).thenReturn(Flux.just(
                new ChatEvent(ChatEvent.Type.TEXT, "你", null),
                new ChatEvent(ChatEvent.Type.TEXT, "好", null),
                new ChatEvent(ChatEvent.Type.TEXT, "！", null),
                new ChatEvent(ChatEvent.Type.USAGE, null, ModelUsage.empty())));
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
        List<ConversationMessage> history = memory.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(history.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
        assertThat(history.get(1).text()).isEqualTo("你好！");
    }

    /** 迭代 15 I15-7：reasoning_content 走独立 reasoning 事件，与文本事件分离。 */
    @Test
    void streamEmitsReasoningEventSeparately() throws Exception {
        when(chatService.stream(any())).thenReturn(Flux.just(
                new ChatEvent(ChatEvent.Type.REASONING, "我需要先查看文件再回答", null),
                new ChatEvent(ChatEvent.Type.TEXT, "最终回复", null),
                new ChatEvent(ChatEvent.Type.USAGE, null, ModelUsage.empty())));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"看看文件\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(mvcResult -> {
                    String body = mvcResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(body).contains("event:reasoning")
                            .contains("我需要先查看文件再回答")
                            .contains("最终回复");
                });
    }

    /** 上游异常：2 秒内完成收尾（不挂起），客户端拿到异常，注册表清空。 */
    @Test
    void upstreamErrorCompletesWithoutHanging() throws Exception {
        when(chatService.stream(any())).thenReturn(
                Flux.<ChatEvent>error(new RuntimeException("上游 API 超时"))
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
        when(chatService.stream(any())).thenReturn(Flux.never());
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
        when(registry.slotFor("qwen")).thenReturn(slot("qwen", false));
        when(registry.slotFor("gpt-5")).thenThrow(
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
    void modelRoutingUsesSelectedSlot() throws Exception {
        ModelSlot qwen = slot("qwen", true);
        when(registry.slotFor("qwen")).thenReturn(qwen);
        when(chatService.call(any())).thenReturn(new ChatResult("通义回复", "", ModelUsage.empty()));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"model\":\"qwen\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentScopeChatService.ChatRequest> captor =
                ArgumentCaptor.forClass(AgentScopeChatService.ChatRequest.class);
        verify(chatService).call(captor.capture());
        assertThat(captor.getValue().slot()).isEqualTo(qwen);
    }

    /** 迭代 13 I13-5：请求级临时凭据路由到 ephemeralClient，不与共享缓存混用。 */
    @Test
    void ephemeralCredentialRoutesToRequestSlot() throws Exception {
        when(chatService.call(any())).thenReturn(new ChatResult("临时回复", "", ModelUsage.empty()));
        ChatController controller = newController(defaultProps(), new InMemorySessionMemory(20, 64, 16000));
        MockMvc mockMvc = mockMvcFor(controller);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"credential\":{\"protocol\":\"openai\","
                                + "\"baseUrl\":\"https://api.deepseek.com\",\"model\":\"deepseek-v4-flash\","
                                + "\"temperature\":0.7,\"apiKey\":\"sk-ephemeral\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AgentScopeChatService.ChatRequest> captor =
                ArgumentCaptor.forClass(AgentScopeChatService.ChatRequest.class);
        verify(chatService).call(captor.capture());
        assertThat(captor.getValue().slot().protocol()).isEqualTo("openai");
        assertThat(captor.getValue().slot().apiKey()).isEqualTo("sk-ephemeral");
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
        when(chatService.call(any())).thenReturn(
                new ChatResult("好的，已记录", "", ModelUsage.empty()),
                new ChatResult("你叫小明", "", ModelUsage.empty()));
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

        ArgumentCaptor<AgentScopeChatService.ChatRequest> captor =
                ArgumentCaptor.forClass(AgentScopeChatService.ChatRequest.class);
        verify(chatService, atLeast(2)).call(captor.capture());
        List<ConversationMessage> replay = captor.getValue().history();
        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).role()).isEqualTo(ConversationMessage.Role.USER);
        assertThat(replay.get(0).text()).contains("记住");
        assertThat(replay.get(1).role()).isEqualTo(ConversationMessage.Role.ASSISTANT);
    }
}
