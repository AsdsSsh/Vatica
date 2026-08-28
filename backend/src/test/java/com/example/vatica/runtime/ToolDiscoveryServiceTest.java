package com.example.vatica.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.example.vatica.agentscope.AgentScopeToolGroupAdapter;
import com.example.vatica.config.ToolDiscoveryProperties;
import com.example.vatica.knowledge.EmbeddingGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

/** 迭代 32A/32B/32C：工具硬过滤、混合召回、完整 Schema 装箱和一次性渐进发现。 */
class ToolDiscoveryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void appliesHardAllowlistBeforeRankingAndSupportsExplicitEmptyRestriction() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 8, 8, 1, 0, 0, 1d, 0d, 0d));
        AgentTool calculator = tool("calculator", "计算金额");
        AgentTool mail = tool("mail_send", "发送邮件");

        ToolDiscoveryService.DiscoverySession allowlisted = service.open(
                new AgentTool[] { calculator, mail }, "发送邮件", List.of(" calculator "));
        assertThat(allowlisted.initial().selectedToolNames()).containsExactly("calculator");
        assertThat(allowlisted.hasMore()).isFalse();

        ToolDiscoveryService.DiscoverySession explicitlyEmpty = service.open(
                new AgentTool[] { calculator, mail }, "发送邮件", List.of(), true);
        assertThat(explicitlyEmpty.initial().selected()).isEmpty();
        assertThat(explicitlyEmpty.hasMore()).isFalse();
        assertThat(explicitlyEmpty.searchTool()).isNull();
    }

    @Test
    void deDuplicatesToolsKeepingTheFirstDefinition() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 8, 8, 1, 0, 0, 1d, 0d, 0d));
        AgentTool first = tool("same_name", "第一份定义");
        AgentTool duplicate = tool("same_name", "第二份定义");
        AgentTool other = tool("other_name", "另一个工具");

        ToolDiscoveryService.DiscoveryResult result = service.discover(
                List.of(first, duplicate, other), "", List.of(), 0, 8);

        assertThat(result.selected()).contains(first, other).doesNotContain(duplicate);
        assertThat(result.ranked()).extracting(ToolDiscoveryService.RankedTool::name)
                .containsExactly("same_name", "other_name");
    }

    @Test
    void usesEmbeddingToFindAChineseSynonymWhenLexicalMatchIsAbsent() {
        StubEmbedding embedding = new StubEmbedding();
        embedding.put("查找会议安排", vector(1f, 0f));
        embedding.put("查询日历", vector(.99f, .01f));
        embedding.put("发送邮件", vector(0f, 1f));
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, .2d, .8d, .1d));

        ToolDiscoveryService.DiscoveryResult result = service.discover(
                List.of(tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件")),
                "查找会议安排", List.of(), 0, 1);

        assertThat(result.semanticUsed()).isTrue();
        assertThat(result.selectedToolNames()).containsExactly("calendar_query");
        assertThat(result.ranked().getFirst().name()).isEqualTo("calendar_query");
        assertThat(result.ranked().getFirst().semanticScore()).isGreaterThan(.9d);
        assertThat(result.ranked().getFirst().semanticScore())
                .isGreaterThan(result.ranked().get(1).semanticScore());
    }

    @Test
    void fallsBackToLexicalRankingWhenEmbeddingThrows() {
        StubEmbedding embedding = new StubEmbedding();
        embedding.fail = true;
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, 1d, 0d, .1d));

        ToolDiscoveryService.DiscoveryResult result = service.discover(
                List.of(tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件")),
                "发送邮件", List.of(), 0, 1);

        assertThat(result.semanticUsed()).isFalse();
        assertThat(result.selectedToolNames()).containsExactly("mail_send");
        assertThat(result.ranked().getFirst().lexicalScore()).isGreaterThan(0d);
        assertThat(result.ranked()).allSatisfy(candidate ->
                assertThat(candidate.semanticScore()).isZero());
    }

    @Test
    void doesNotReportSemanticUsageForBlankQuery() {
        StubEmbedding embedding = new StubEmbedding();
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, .5d, .5d, 0d));

        ToolDiscoveryService.DiscoveryResult result = service.discover(
                List.of(tool("calendar_query", "查询日历")), "   ", List.of(), 0, 8);

        assertThat(result.semanticUsed()).isFalse();
        assertThat(embedding.calls).hasValue(0);
    }

    @Test
    void packsOnlyCompleteSchemasWithinBudget() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 8, 8, 1, 0, 0, 1d, 0d, 0d));
        Map<String, Object> parameters = Map.of("type", "object",
                "properties", Map.of("date", Map.of("type", "string", "description", "会议日期")),
                "required", List.of("date"));
        ToolSchema calendar = schema("calendar_query", "查询日历", parameters);
        ToolSchema mail = schema("mail_send", "发送邮件", Map.of("type", "object"));
        int budget = service.estimateSchemaTokens(calendar);

        ToolDiscoveryService.SchemaSelection selection = service.selectSchemas(
                List.of(calendar, mail), "calendar_query", budget);

        assertThat(selection.selected()).containsExactly(calendar);
        assertThat(selection.omittedToolNames()).containsExactly("mail_send");
        assertThat(selection.estimatedSchemaTokens()).isLessThanOrEqualTo(budget);
        // 返回原始 Schema 对象，参数结构保持完整，不做字段级截断或重写。
        assertThat(selection.selected().getFirst().getParameters()).isEqualTo(parameters);
    }

    @Test
    void reportsSchemaSemanticUsageOnlyWhenEmbeddingQuerySucceeds() {
        StubEmbedding embedding = new StubEmbedding();
        embedding.fail = true;
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, .5d, .5d, 0d));
        ToolSchema calendar = schema("calendar_query", "查询日历", Map.of("type", "object"));

        ToolDiscoveryService.SchemaSelection selection = service.selectSchemas(
                List.of(calendar), "查找会议", 0);

        assertThat(selection.semanticUsed()).isFalse();
    }

    @Test
    void estimatesNullDescriptionsWithoutThrowing() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 8, 8, 1, 1000, 1000, 1d, 0d, 0d));
        AgentTool nullDescriptionTool = tool("null_description", null);
        ToolSchema nullDescriptionSchema = schema("null_schema", null, Map.of("type", "object"));

        assertThat(service.discover(List.of(nullDescriptionTool), "工具", List.of(), 0, 1)
                .selectedToolNames()).containsExactly("null_description");
        assertThat(service.estimateSchemaTokens(nullDescriptionSchema)).isGreaterThan(0);
        assertThat(service.selectSchemas(List.of(nullDescriptionSchema), "工具", 1000)
                .selected()).containsExactly(nullDescriptionSchema);
    }

    @Test
    void fallsBackPerCandidateWhenOnlyTheQueryEmbeddingSucceeds() {
        StubEmbedding embedding = new StubEmbedding();
        embedding.put("发送邮件", vector(1f, 0f));
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, .2d, .8d, 0d));

        ToolDiscoveryService.DiscoveryResult result = service.discover(
                List.of(tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件工具")),
                "发送邮件", List.of(), 0, 1);

        assertThat(result.semanticUsed()).isFalse();
        assertThat(result.selectedToolNames()).containsExactly("mail_send");
        assertThat(result.ranked()).allSatisfy(candidate ->
                assertThat(candidate.semanticScore()).isZero());
    }

    @Test
    void alwaysReservesCompleteSearchSchemaBeforePackingOrdinarySchemas() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        ToolSchema calculator = schema("calculator", "计算金额", Map.of("type", "object"));
        ToolSchema search = schema(ToolDiscoveryService.SEARCH_TOOLS_NAME,
                "查找额外工具", Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string"))));
        int budget = service.estimateSchemaTokens(calculator) + service.estimateSchemaTokens(search);

        ToolDiscoveryService.SchemaSelection selection = service.selectSchemas(
                List.of(calculator, search), "计算金额", budget);

        assertThat(selection.selected()).containsExactly(calculator, search);
        assertThat(selection.estimatedSchemaTokens()).isLessThanOrEqualTo(budget);
        assertThat(selection.selected().get(1).getParameters()).isSameAs(search.getParameters());
    }

    @Test
    void keepsSearchSchemaWhenOrdinaryToolCountIsAlreadyAtTheConfiguredLimit() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        ToolSchema calendar = schema("calendar_query", "查询日历", Map.of("type", "object"));
        ToolSchema mail = schema("mail_send", "发送邮件", Map.of("type", "object"));
        ToolSchema search = schema(ToolDiscoveryService.SEARCH_TOOLS_NAME,
                "查找额外工具", Map.of("type", "object"));

        ToolDiscoveryService.SchemaSelection selection = service.selectSchemas(
                List.of(calendar, mail, search), "查询日历", 10_000);

        assertThat(selection.selected()).extracting(ToolSchema::getName)
                .containsExactly("calendar_query", ToolDiscoveryService.SEARCH_TOOLS_NAME);
        assertThat(selection.omittedToolNames()).containsExactly("mail_send");
    }

    @Test
    void cachesEmbeddingVectorsAcrossRepeatedDiscovery() {
        StubEmbedding embedding = new StubEmbedding();
        embedding.put("查找会议", vector(1f, 0f));
        embedding.put("查询日历", vector(1f, 0f));
        embedding.put("发送邮件", vector(0f, 1f));
        ToolDiscoveryService service = new ToolDiscoveryService(embedding,
                properties(true, 8, 8, 1, 0, 0, .5d, .5d, 0d));
        List<AgentTool> tools = List.of(tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件"));

        service.discover(tools, "查找会议", List.of(), 0, 8);
        int firstCallCount = embedding.calls.get();
        service.discover(tools, "查找会议", List.of(), 0, 8);

        assertThat(firstCallCount).isEqualTo(3); // query + 两个工具描述
        assertThat(embedding.calls).hasValue(firstCallCount);
    }

    @Test
    void searchToolExpandsOnceAndLoadsOnlyRemainingAuthorizedTools() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        AgentTool calendar = tool("calendar_query", "查询日历");
        AgentTool mail = tool("mail_send", "发送邮件");
        AgentTool todo = tool("todo_add", "添加待办");
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { calendar, mail, todo }, "查询日历", List.of());
        assertThat(session.initial().selectedToolNames()).containsExactly("calendar_query");
        assertThat(session.hasMore()).isTrue();

        AtomicReference<List<AgentTool>> loaded = new AtomicReference<>(List.of());
        session.bindLoader(tools -> loaded.set(List.copyOf(tools)));
        AgentTool search = session.searchTool();
        assertThat(search).isNotNull();

        ToolResultBlock first = search.callAsync(call(Map.of("query", "发送邮件"))).block();
        assertThat(text(first)).contains("mail_send", "发送邮件");
        assertThat(loaded.get()).extracting(AgentTool::getName).containsExactly("mail_send");
        assertThat(session.expansionsUsed()).isEqualTo(1);
        assertThat(session.hasMore()).isTrue();

        ToolResultBlock second = search.callAsync(call(Map.of("query", "添加待办"))).block();
        assertThat(text(second)).contains("次数已用尽");
        assertThat(loaded.get()).extracting(AgentTool::getName).containsExactly("mail_send");
    }

    @Test
    void blankSearchDoesNotConsumeTheOnlyExpansion() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件") },
                "查询日历", List.of());
        session.initial();
        AgentTool search = session.searchTool();

        assertThat(text(search.callAsync(call(Map.of("query", "   "))).block())).contains("不能为空");
        assertThat(session.expansionsUsed()).isZero();
        assertThat(text(search.callAsync(call(Map.of("query", "发送邮件"))).block())).contains("mail_send");
        assertThat(session.expansionsUsed()).isEqualTo(1);
    }

    @Test
    void searchToolDynamicallyMountsResultForTheNextToolkitRound() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        AgentTool calendar = tool("calendar_query", "查询日历");
        AgentTool mail = tool("mail_send", "发送邮件");
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { calendar, mail }, "查询日历", List.of());

        List<AgentTool> initial = new ArrayList<>(session.initial().selected());
        AgentTool search = session.searchTool();
        assertThat(search).isNotNull();
        initial.add(search);

        Toolkit toolkit = new Toolkit();
        AgentScopeToolGroupAdapter.Registration registration =
                AgentScopeToolGroupAdapter.registerRequestScoped(toolkit,
                        initial.toArray(AgentTool[]::new),
                        AgentScopeToolGroupAdapter.candidateNames(initial.toArray(AgentTool[]::new)));
        session.bindLoader(selected -> selected.forEach(tool ->
                toolkit.registration().agentTool(tool).group(registration.allowedGroup()).apply()));

        // 首轮模型只会看到初始候选和受控搜索入口。
        assertThat(toolkit.getToolSchemas()).extracting(ToolSchema::getName)
                .containsExactlyInAnyOrder("calendar_query", ToolDiscoveryService.SEARCH_TOOLS_NAME)
                .doesNotContain("mail_send");

        ToolResultBlock discovery = toolkit.callTool(call(ToolDiscoveryService.SEARCH_TOOLS_NAME,
                Map.of("query", "发送邮件"))).block();

        assertThat(text(discovery)).contains("mail_send");
        // search_tools 完成后，同一个请求级 ToolGroup 已拥有新工具；下一轮模型会生成该 Schema，
        // 并且执行链也能实际调用它，而不是只返回一段提示词。
        assertThat(toolkit.getToolSchemas()).extracting(ToolSchema::getName)
                .contains("calendar_query", ToolDiscoveryService.SEARCH_TOOLS_NAME, "mail_send");
        ToolResultBlock execution = toolkit.callTool(call("mail_send", Map.of())).block();
        assertThat(text(execution)).contains("mail_send result");
    }

    @Test
    void searchToolReturnsSafeBoundedDescriptionAndPreservesStateWhenLoaderFails() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 0, 0, 1d, 0d, 0d));
        String longDescription = "发送邮件 " + "内部内容".repeat(200);
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { tool("mail_send", longDescription), tool("todo_add", "添加待办") },
                "添加待办", List.of());
        session.initial();
        session.bindLoader(ignored -> {
            throw new IllegalStateException("simulated mount failure");
        });
        AgentTool search = session.searchTool();

        ToolResultBlock result = search.callAsync(call(Map.of("query", "发送邮件"))).block();

        assertThat(text(result)).contains("挂载失败");
        assertThat(session.hasMore()).isTrue();
        assertThat(session.expansionsUsed()).isEqualTo(1);
    }

    @Test
    void budgetFailureDoesNotConsumeExpansionQuota() {
        // 初轮预算足以保留 search_tools；搜索阶段预算故意小于其完整 Schema。
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 1, 1, 1000, 1, 1d, 0d, 0d));
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { tool("calendar_query", "查询日历"), tool("mail_send", "发送邮件") },
                "查询日历", List.of());
        assertThat(session.searchTool()).isNotNull();

        ToolResultBlock result = session.searchTool().callAsync(call(Map.of("query", "发送邮件"))).block();

        assertThat(text(result)).contains("Schema 预算");
        assertThat(session.expansionsUsed()).isZero();
        assertThat(session.hasMore()).isTrue();
    }

    @Test
    void rollsBackPartiallyMountedBatchWhenLoaderFails() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(true, 1, 2, 1, 1000, 1000, 1d, 0d, 0d));
        AgentTool calendar = tool("calendar_query", "查询日历");
        AgentTool mail = tool("mail_send", "发送邮件");
        ToolDiscoveryService.DiscoverySession session = service.open(
                new AgentTool[] { calendar, mail }, "查询日历", List.of());
        session.initial();
        AtomicReference<List<AgentTool>> mounted = new AtomicReference<>(List.of());
        session.bindLoader(new ToolDiscoveryService.ToolLoader() {
            @Override
            public void load(List<AgentTool> tools) {
                mounted.set(List.copyOf(tools));
                // 模拟第二个工具落地后，底层 ToolGroup 注册失败。
                throw new IllegalStateException("simulated partial mount failure");
            }

            @Override
            public void rollback(List<AgentTool> tools) {
                mounted.set(List.of());
            }
        });

        ToolResultBlock result = session.searchTool().callAsync(call(Map.of("query", "发送邮件"))).block();

        assertThat(text(result)).contains("挂载失败");
        assertThat(mounted).hasValue(List.of());
        assertThat(session.expansionsUsed()).isEqualTo(1);
        assertThat(session.hasMore()).isTrue();
    }

    @Test
    void disablesSearchMetaToolWhenExpansionBudgetIsZero() {
        ToolDiscoveryService service = new ToolDiscoveryService((EmbeddingGateway) null,
                properties(false, 8, 8, 0, 0, 0, 1d, 0d, 0d));

        assertThat(service.enabledValue()).isFalse();
        assertThat(service.searchEnabled()).isFalse();
        assertThat(service.open(new AgentTool[] { tool("mail_send", "发送邮件") }, "邮件", List.of())
                .searchTool()).isNull();
    }

    private static ToolDiscoveryProperties properties(boolean enabled, int maxInitialTools,
            int maxSearchResults, int maxExpansions, int maxInitialSchemaTokens,
            int maxSearchSchemaTokens, double lexicalWeight, double semanticWeight,
            double minimumScore) {
        return new ToolDiscoveryProperties(enabled, maxInitialTools, maxSearchResults, maxExpansions,
                maxInitialSchemaTokens, maxSearchSchemaTokens, lexicalWeight, semanticWeight, minimumScore);
    }

    private static AgentTool tool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Map<String, Object> getOutputSchema() {
                return Map.of();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text(name + " result"));
            }
        };
    }

    private static ToolSchema schema(String name, String description, Map<String, Object> parameters) {
        ToolSchema schema = mock(ToolSchema.class);
        when(schema.getName()).thenReturn(name);
        when(schema.getDescription()).thenReturn(description);
        when(schema.getParameters()).thenReturn(parameters);
        when(schema.getOutputSchema()).thenReturn(Map.of());
        return schema;
    }

    private static ToolCallParam call(Map<String, Object> input) {
        return call(ToolDiscoveryService.SEARCH_TOOLS_NAME, input);
    }

    private static ToolCallParam call(String name, Map<String, Object> input) {
        ToolUseBlock use = ToolUseBlock.builder().id("test-search-tools")
                .name(name).input(input).content(json(input)).build();
        return ToolCallParam.builder().toolUseBlock(use).input(input).build();
    }

    private static String json(Map<String, Object> input) {
        try {
            return JSON.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("测试工具参数序列化失败", e);
        }
    }

    private static String text(ToolResultBlock result) {
        return result == null || result.getOutput() == null ? ""
                : result.getOutput().stream().map(String::valueOf).collect(Collectors.joining("\n"));
    }

    private static float[] vector(float... values) {
        return values;
    }

    private static final class StubEmbedding implements EmbeddingGateway {
        private final Map<String, float[]> vectors = new HashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private boolean fail;

        void put(String text, float[] vector) {
            vectors.put(text, vector.clone());
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("embedding unavailable");
            }
            float[] value = vectors.get(text);
            if (value == null) {
                throw new IllegalArgumentException("no fixture for " + text);
            }
            return value.clone();
        }

        @Override
        public int dimensions() {
            return 2;
        }
    }
}
