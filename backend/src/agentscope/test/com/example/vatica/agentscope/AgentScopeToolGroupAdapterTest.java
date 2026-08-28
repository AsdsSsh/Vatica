package com.example.vatica.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.permission.FilePermissionContext;
import com.example.vatica.permission.FilePermissionMode;
import com.example.vatica.permission.FilePermissionPolicy;
import com.example.vatica.permission.PermissionBoundToolCallbacks;
import com.example.vatica.runtime.ToolDiscoveryService;
import com.example.vatica.tool.AgentToolProvider;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

/** 迭代 30C：请求级工具组和 Vatica 权限包装边界测试。 */
class AgentScopeToolGroupAdapterTest {

    @AfterEach
    void clearContexts() {
        FilePermissionContext.clear();
        RequestIdentityContext.clear();
    }

    @Test
    void exposesOnlyAllowedSchemasAndRejectsDirectCallToDeniedTool() {
        AtomicInteger deniedCalls = new AtomicInteger();
        AgentTool calculator = tool("calculator", deniedCalls);
        AgentTool textStats = tool("text_stats", new AtomicInteger());
        Toolkit toolkit = new Toolkit();

        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, new AgentTool[] { calculator, textStats }, Set.of("calculator"));

        assertThat(registration.restricted()).isTrue();
        assertThat(registration.selectedToolNames()).containsExactly("calculator");
        assertThat(registration.missingAllowedToolNames()).isEmpty();
        assertThat(toolkit.getActiveGroups()).containsExactly(registration.allowedGroup());
        assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName())
                .containsExactly("calculator");

        ToolResultBlock result = toolkit.callTool(call("text_stats")).block();
        assertThat(result).isNotNull();
        assertThat(result.getOutput()).anySatisfy(output ->
                assertThat(output.toString()).contains("Unauthorized tool call"));
        assertThat(deniedCalls).hasValue(0);
    }

    @Test
    void permissionWrapperRemainsInExecutionChainForActiveTool() {
        AtomicReference<FilePermissionContext.Snapshot> permissionSeen = new AtomicReference<>();
        AtomicReference<RequestIdentity> identitySeen = new AtomicReference<>();
        AgentTool delegate = new AgentTool() {
            @Override public String getName() { return "read_file"; }
            @Override public String getDescription() { return "读取文件"; }
            @Override public Map<String, Object> getParameters() { return Map.of("type", "object"); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                permissionSeen.set(FilePermissionContext.current());
                identitySeen.set(RequestIdentityContext.current());
                return Mono.just(ToolResultBlock.text("ok"));
            }
        };
        FilePermissionPolicy policy = new FilePermissionPolicy(FilePermissionMode.WORKSPACE_WRITE, java.util.List.of());
        RequestIdentity identity = new RequestIdentity(7L, 9L, "MEMBER", "tester");
        AgentToolProvider provider = () -> new AgentTool[] { delegate };
        AgentTool[] wrapped = PermissionBoundToolCallbacks.wrap(provider, policy, "chat:30c", identity, null);
        Toolkit toolkit = new Toolkit();

        AgentScopeToolGroupAdapter.register(toolkit, wrapped, Set.of("read_file"));
        ToolResultBlock result = toolkit.callTool(call("read_file")).block();

        assertThat(result).isNotNull();
        assertThat(result.getOutput()).anySatisfy(output -> assertThat(output.toString()).contains("ok"));
        assertThat(permissionSeen).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.policy()).isEqualTo(policy);
            assertThat(snapshot.channel()).isEqualTo("chat:30c");
        });
        assertThat(identitySeen).hasValue(identity);
        assertThat(FilePermissionContext.current()).isNull();
        assertThat(RequestIdentityContext.current()).isNull();
    }

    @Test
    void emptyAllowlistKeepsLegacyUngroupedRegistrationAndFilter() {
        AgentTool first = tool("calculator", new AtomicInteger());
        AgentTool duplicate = tool("calculator", new AtomicInteger());
        AgentTool second = tool("text_stats", new AtomicInteger());
        Toolkit toolkit = new Toolkit();

        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, new AgentTool[] { first, duplicate, second }, Set.of());

        assertThat(registration.restricted()).isFalse();
        assertThat(registration.allowedGroup()).isNull();
        assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName())
                .containsExactly("calculator", "text_stats");
        assertThat(AgentScopeToolGroupAdapter.filter(
                new AgentTool[] { first, duplicate, second }, Set.of("text_stats")))
                .extracting(AgentTool::getName).containsExactly("text_stats");
    }

    @Test
    void whitespaceOnlyAllowlistUsesTheSameEmptySemantics() {
        Toolkit toolkit = new Toolkit();
        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, new AgentTool[] { tool("calculator", new AtomicInteger()) }, Set.of(" "));

        assertThat(registration.restricted()).isFalse();
        assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName())
                .containsExactly("calculator");
    }

    @Test
    void scopedActivationKeepsPreexistingUngroupedToolsRegistered() {
        Toolkit toolkit = new Toolkit();
        AgentTool calculator = tool("calculator", new AtomicInteger());
        toolkit.registerAgentTool(calculator);

        AgentScopeToolGroupAdapter.ScopedActivation activation =
                AgentScopeToolGroupAdapter.activate(toolkit, Set.of("calculator"));
        activation.close();

        assertThat(toolkit.getTool("calculator")).isSameAs(calculator);
        assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName())
                .containsExactly("calculator");
    }

    @Test
    void reportsAllowedNamesThatAreNotAvailableWithoutExpandingAccess() {
        Toolkit toolkit = new Toolkit();
        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, new AgentTool[] { tool("calculator", new AtomicInteger()) },
                Set.of("calculator", "mail_send"));

        assertThat(registration.selectedToolNames()).containsExactly("calculator");
        assertThat(registration.missingAllowedToolNames()).containsExactly("mail_send");
        assertThat(toolkit.getToolSchemas()).extracting(schema -> schema.getName())
                .containsExactly("calculator");
    }

    @Test
    void explicitEmptyRestrictedSetExposesNoTools() {
        Toolkit toolkit = new Toolkit();

        AgentScopeToolGroupAdapter.Registration registration = AgentScopeToolGroupAdapter.register(
                toolkit, new AgentTool[] { tool("calculator", new AtomicInteger()) }, Set.of(), true);

        assertThat(registration.restricted()).isTrue();
        assertThat(registration.selectedToolNames()).isEmpty();
        assertThat(toolkit.getToolSchemas()).isEmpty();
    }

    @Test
    void transactionalLoaderRemovesEarlierRegistrationsAfterPartialFailure() {
        Toolkit toolkit = new Toolkit();
        String groupName = "dynamic-test";
        toolkit.createToolGroup(groupName, "dynamic test", false);
        AgentTool first = tool("first", new AtomicInteger());
        AgentTool second = tool("second", new AtomicInteger());
        ToolDiscoveryService.ToolLoader delegate =
                AgentScopeToolGroupAdapter.transactionalLoader(toolkit, groupName);
        ToolDiscoveryService.ToolLoader failing = new ToolDiscoveryService.ToolLoader() {
            @Override
            public void load(List<AgentTool> tools) {
                delegate.load(List.of(tools.getFirst()));
                throw new IllegalStateException("simulated second registration failure");
            }

            @Override
            public void rollback(List<AgentTool> tools) {
                delegate.rollback(tools);
            }
        };

        try {
            failing.load(List.of(first, second));
        } catch (IllegalStateException expected) {
            failing.rollback(List.of(first, second));
        }

        assertThat(toolkit.getTool(first.getName())).isNull();
        assertThat(toolkit.getToolGroup(groupName).containsTool(first.getName())).isFalse();
    }

    @Test
    void rejectsBlankToolNameInsteadOfCreatingUnboundEntry() {
        AgentTool invalid = tool(" ", new AtomicInteger());

        assertThatThrownBy(() -> AgentScopeToolGroupAdapter.filter(new AgentTool[] { invalid }, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名不能为空");
    }

    private static AgentTool tool(String name, AtomicInteger calls) {
        return new AgentTool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "测试工具 " + name; }
            @Override public Map<String, Object> getParameters() { return Map.of("type", "object"); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                calls.incrementAndGet();
                return Mono.just(ToolResultBlock.text(name + " 执行结果"));
            }
        };
    }

    private static ToolCallParam call(String name) {
        ToolUseBlock use = ToolUseBlock.builder()
                .id("test-" + name)
                .name(name)
                .input(Map.of())
                .content("{}")
                .build();
        return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
    }
}
