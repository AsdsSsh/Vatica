package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.vatica.agent.JudgeAgent;
import com.example.vatica.agent.PlannerAgent;
import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.runtime.AgentRuntimeFactory;
import com.example.vatica.observability.AgentSpanRecord;
import com.example.vatica.observability.AgentSpanRecordRepository;
import com.example.vatica.task.TaskPlan;
import com.example.vatica.task.TaskPlan.TaskStep;
import com.example.vatica.task.TaskRecord;
import com.example.vatica.task.TaskRecordRepository;
import com.example.vatica.task.TaskService;
import com.example.vatica.task.TaskStatus;
import com.example.vatica.task.TaskVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 17A：任务状态机到 AgentScope ReAct 再到结果落库的零外网端到端验收。 */
@SpringBootTest(properties = {
        "vatica.mcp.client.enabled=false",
        "vatica.model.openai.api-key=test-key",
        "vatica.model.openai.chat.model=test-model",
        "vatica.model.openai.chat.temperature=0.1",
        "vatica.app.state-dir=./target/test-state/agentscope-e2e-${random.uuid}",
        "spring.datasource.url=jdbc:h2:mem:vatica-agentscope-e2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
class TaskServiceAgentScopeE2eTest {

    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final HttpServer MODEL_SERVER = startModelServer();

    @DynamicPropertySource
    static void modelEndpoint(DynamicPropertyRegistry properties) {
        properties.add("vatica.model.openai.base-url",
                () -> "http://127.0.0.1:" + MODEL_SERVER.getAddress().getPort() + "/v1");
    }

    @MockitoBean
    PlannerAgent plannerAgent;
    @MockitoBean
    JudgeAgent judgeAgent;

    @Autowired
    TaskService taskService;
    @Autowired
    TaskRecordRepository repository;
    @Autowired
    AgentRuntimeFactory runtimeFactory;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AgentSpanRecordRepository spanRepository;

    @BeforeEach
    void setUp() {
        RequestIdentityContext.set(new RequestIdentity(17L, 21L, "MEMBER", "learner"));
        repository.deleteAll();
        spanRepository.deleteAll();
        MODEL_CALLS.set(0);
        LAST_REQUEST.set(null);
        TaskStep step = new TaskStep(1, "汇总已核验的信息", false);
        step.setAgent("research");
        TaskPlan plan = new TaskPlan();
        plan.setSteps(List.of(step));
        when(plannerAgent.plan("完成 AgentScope 端到端验收"))
                .thenReturn(plan);
        when(judgeAgent.evaluate(anyString(), any(), any()))
                .thenReturn(new JudgeAgent.Evaluation(90, TaskVerdict.PASS, "验收通过"));
    }

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @AfterAll
    static void stopServer() {
        MODEL_SERVER.stop(0);
    }

    @Test
    void realTaskRunsThroughAgentScopeAndPersistsResult() throws Exception {
        TaskRecord created = taskService.create("完成 AgentScope 端到端验收");
        TaskRecord done = taskService.approve(created.getId());

        assertThat(runtimeFactory.runtime().name()).isEqualTo("agentscope");
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getScore()).isEqualTo(90);
        TaskPlan persisted = mapper.readValue(
                repository.findById(created.getId()).orElseThrow().getPlanJson(), TaskPlan.class);
        assertThat(persisted.getSteps().getFirst().getAgent()).isEqualTo("research");
        assertThat(persisted.getSteps().getFirst().getSkillId()).isEqualTo("knowledge-research");
        assertThat(persisted.getSteps().getFirst().getSkillVersion()).isEqualTo("1.1.0");
        assertThat(persisted.getSteps().getFirst().getResult()).isEqualTo("AgentScope 端到端完成");
        assertThat(MODEL_CALLS).hasValue(1);
        assertThat(LAST_REQUEST.get()).contains("汇总已核验的信息", "knowledge-research@1.1.0",
                "search_knowledge_base").doesNotContain("calculator", "text_stats");
        List<AgentSpanRecord> spans = spanRepository
                .findByUserIdAndOrgIdAndTaskIdOrderByStartedAtAscSpanIdAsc(17L, 21L, created.getId());
        assertThat(spans).extracting(AgentSpanRecord::getSpanType)
                .contains("TASK_RUN", "PLANNER", "HITL_WAIT", "WAVE", "AGENT_STEP", "MODEL_CALL", "JUDGE");
        assertThat(spans).filteredOn(span -> "MODEL_CALL".equals(span.getSpanType()))
                .singleElement().satisfies(span -> {
                    assertThat(span.getRuntime()).isEqualTo("agentscope");
                    assertThat(span.getTotalTokens()).isEqualTo(17);
                });
    }

    private static HttpServer startModelServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", TaskServiceAgentScopeE2eTest::respond);
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        MODEL_CALLS.incrementAndGet();
        byte[] response = """
                {"id":"chatcmpl-e2e","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"AgentScope 端到端完成"},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
