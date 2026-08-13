package com.example.vatica.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.example.vatica.config.ChatProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;

/**
 * 对话控制器单测（迭代 2.5 I2.5-1/I2.5-3）：SSE 错误传播 / 超时 / 断连清理 + 会话历史回放。
 *
 * <p>这里测的是迭代 2.5 之后控制器里"真实存在"的错误处理逻辑（错误路径而非转发），
 * 是规划文档 7.2"胶水层不测"规则的例外；LLM 行为仍 mock 化。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerTest {

    @Mock
    ChatClient.Builder builder;
    @Mock
    ChatClient chatClient;
    @Mock
    ChatClient.ChatClientRequestSpec spec;
    @Mock
    ChatClient.StreamResponseSpec streamSpec;
    @Mock
    ChatClient.CallResponseSpec callSpec;
    @Mock
    ToolCallbackProvider tools;

    @BeforeEach
    void stubCommonChain() {
        when(builder.defaultTools(any(ToolCallbackProvider.class))).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
    }

    private ChatProperties defaultProps() {
        return new ChatProperties(
                new ChatProperties.Sse(Duration.ofMinutes(5)),
                new ChatProperties.Memory(20, 64, 16000));
    }

    private ChatController newController(ChatProperties props, SessionMemory memory) {
        return new ChatController(builder, tools, props, memory);
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
        SessionMemory memory = new SessionMemory(20, 64, 16000);
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
        ChatController controller = newController(defaultProps(), new SessionMemory(20, 64, 16000));
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
        ChatController controller = newController(props, new SessionMemory(20, 64, 16000));

        SseEmitter emitter = controller.stream(new ChatRequest("你好", null));

        assertThat(emitter.getTimeout()).isEqualTo(30_000L);
        assertThat(controller.activeStreamCount()).isEqualTo(1); // 挂起期间计入活跃连接
    }

    /** 非流式：第二轮请求自动带上第一轮历史（user + assistant）。 */
    @Test
    void chatAppendsAndReplaysHistory() throws Exception {
        when(callSpec.content()).thenReturn("好的，已记录");
        when(spec.call()).thenReturn(callSpec);
        SessionMemory memory = new SessionMemory(20, 64, 16000);
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

