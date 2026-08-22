package com.example.vatica.capability;

import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.config.McpProperties;
import com.example.vatica.config.McpToolProviderGuard;
import com.example.vatica.config.ModelRegistry;
import com.example.vatica.config.ModelSlot;
import com.example.vatica.knowledge.JdbcKnowledgeVectorIndex;
import com.example.vatica.knowledge.KnowledgeProperties;
import com.example.vatica.mail.MailCredentialMode;
import com.example.vatica.mail.UserMailService;
import com.example.vatica.tool.AgentToolProvider;
import com.example.vatica.workspace.WorkspaceStore;

/**
 * 迭代 23C：将影响 Agent 执行的外部能力收敛为一份可审计、无敏感值的状态快照。
 *
 * <p>本服务不会主动调用模型、远程 MCP、邮件服务器或 embedding 服务；它只检查本地配置、
 * 已初始化组件与数据库/工作区的基础可达性，避免“打开状态页”本身制造副作用。
 */
@Service
public class SystemCapabilityService {

    public enum Status {
        READY,
        ACTION_REQUIRED,
        DEGRADED,
        UNAVAILABLE
    }

    public record CapabilityView(String id, String name, Status status, String message, String action) { }

    public record Snapshot(List<CapabilityView> capabilities) { }

    private final ModelRegistry models;
    private final DataSource dataSource;
    private final KnowledgeProperties knowledge;
    private final JdbcKnowledgeVectorIndex knowledgeIndex;
    private final McpProperties mcp;
    private final ObjectProvider<AgentToolProvider> remoteMcpTools;
    private final UserMailService mail;
    private final WorkspaceStore workspace;

    public SystemCapabilityService(ModelRegistry models, DataSource dataSource, KnowledgeProperties knowledge,
            JdbcKnowledgeVectorIndex knowledgeIndex, McpProperties mcp,
            @Qualifier("remoteMcpTools") ObjectProvider<AgentToolProvider> remoteMcpTools,
            UserMailService mail, WorkspaceStore workspace) {
        this.models = models;
        this.dataSource = dataSource;
        this.knowledge = knowledge;
        this.knowledgeIndex = knowledgeIndex;
        this.mcp = mcp;
        this.remoteMcpTools = remoteMcpTools;
        this.mail = mail;
        this.workspace = workspace;
    }

    public Snapshot snapshot(RequestIdentity identity) {
        List<CapabilityView> capabilities = new ArrayList<>();
        capabilities.add(model());
        capabilities.add(database());
        capabilities.add(knowledge());
        capabilities.add(mcp());
        capabilities.add(mail());
        capabilities.add(workspace(identity));
        return new Snapshot(List.copyOf(capabilities));
    }

    private CapabilityView model() {
        List<ModelSlot> enabled = models.slots().stream().filter(ModelSlot::enabled).toList();
        if (enabled.isEmpty()) {
            return actionRequired("model", "模型", "没有启用的模型。", "在模型设置中启用并配置一个模型。");
        }
        boolean callable = enabled.stream().anyMatch(ModelRegistry::isCallable);
        if (callable) {
            return ready("model", "模型", "至少一个启用模型具备调用凭据或本地端点。");
        }
        return actionRequired("model", "模型", "启用模型缺少调用凭据。", "在模型设置中保存 API Key，或配置本地模型端点。");
    }

    private CapabilityView database() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ready("database", "数据库", "数据库连接正常。");
            }
        } catch (Exception ignored) {
            // 统一返回可执行的恢复提示，不能把地址、账号或驱动错误带到前端。
        }
        return unavailable("database", "数据库", "数据库当前不可用。", "检查 PostgreSQL 地址、账号、网络和服务状态。");
    }

    private CapabilityView knowledge() {
        if (!knowledge.enabled()) {
            return actionRequired("knowledge", "知识库", "知识库已关闭，检索工具不会参与任务规划。", "启用知识库后重启服务。");
        }
        JdbcKnowledgeVectorIndex.Readiness readiness = knowledgeIndex.readiness();
        if (!readiness.ready()) {
            String message = readiness.message() == null || readiness.message().isBlank()
                    ? "pgvector 初始化未完成，检索工具已从任务规划中移除。" : readiness.message();
            String action = readiness.extensionInstalled()
                    ? "执行知识库索引重建后重启服务。"
                    : "确认 PostgreSQL 已安装 vector 扩展后重启服务。";
            return unavailable("knowledge", "知识库", message, action);
        }
        if (!readiness.postgres()) {
            return degraded("knowledge", "知识库", readiness.message() == null
                    ? "当前使用本地 H2 回退索引，不具备 pgvector 检索能力。" : readiness.message(),
                    "切换到已安装 vector 扩展的 PostgreSQL 以启用生产检索。");
        }
        if (!readiness.indexReady()) {
            return degraded("knowledge", "知识库", readiness.message(),
                    "创建 HNSW 索引或执行知识库迁移脚本以恢复索引性能。");
        }
        return ready("knowledge", "知识库", "PostgreSQL pgvector 索引已就绪。");
    }

    private CapabilityView mcp() {
        McpProperties.Client client = mcp.client();
        if (!client.enabled()) {
            return actionRequired("mcp", "MCP", "远程 MCP 客户端未启用。", "在服务配置中启用并配置需要的远程工具。");
        }
        List<McpProperties.Connection> enabled = client.connections().values().stream()
                .filter(McpProperties.Connection::enabled).toList();
        if (enabled.isEmpty()) {
            return actionRequired("mcp", "MCP", "没有启用的远程 MCP 连接。", "在服务配置中添加并启用远程 MCP 连接。");
        }
        boolean valid = enabled.stream().anyMatch(this::mcpConnectionConfigured);
        boolean incomplete = enabled.stream().anyMatch(connection -> !mcpConnectionConfigured(connection));
        AgentToolProvider provider = remoteMcpTools.getIfAvailable();
        if (provider instanceof McpToolProviderGuard guard && guard.isRetryBackoffActive()) {
            return degraded("mcp", "MCP", "远程工具最近发现失败，当前处于退避期；本地工具仍可使用。",
                    "检查远程 MCP 地址和凭据，等待退避结束后会自动重试。");
        }
        if (!valid) {
            return actionRequired("mcp", "MCP", "已启用连接缺少地址或 API Key。", "补全远程 MCP 地址和凭据。");
        }
        if (incomplete) {
            return degraded("mcp", "MCP", "部分远程 MCP 连接尚未配置完整。", "补全未完成连接，或暂时关闭它们。");
        }
        return ready("mcp", "MCP", "远程 MCP 连接已配置，将在任务需要时按需发现工具。");
    }

    private CapabilityView mail() {
        UserMailService.View settings = mail.get();
        boolean endpointsPresent = !settings.imapHost().isBlank() && !settings.smtpHost().isBlank()
                && !settings.username().isBlank();
        if (!endpointsPresent) {
            return actionRequired("mail", "邮件", "邮箱服务器或账号尚未配置。", "在“我的邮箱”中完成服务器和账号配置。");
        }
        if (settings.credentialMode() == MailCredentialMode.ENCRYPTED_AT_REST && settings.passwordSet()) {
            return ready("mail", "邮件", "已保存加密凭据，可在任务中使用邮件工具。");
        }
        if (settings.credentialMode() == MailCredentialMode.EPHEMERAL) {
            return degraded("mail", "邮件", "邮箱采用仅本次凭据模式，任务执行时仍需提供密码。",
                    "在发起任务时提供邮箱密码，或改为加密保存模式。");
        }
        return actionRequired("mail", "邮件", "邮箱密码尚未保存。", "在“我的邮箱”中保存凭据，或改为仅本次模式。");
    }

    private CapabilityView workspace(RequestIdentity identity) {
        try {
            if (Files.isWritable(workspace.root(identity))) {
                return ready("workspace", "工作区", "当前用户工作区可读写。");
            }
        } catch (Exception ignored) {
            // 目录、权限和路径信息均不返回客户端。
        }
        return unavailable("workspace", "工作区", "当前用户工作区不可写。", "检查工作区目录权限后重试。");
    }

    private boolean mcpConnectionConfigured(McpProperties.Connection connection) {
        return !connection.url().isBlank() && (!connection.requiresApiKey() || !connection.apiKey().isBlank());
    }

    private static CapabilityView ready(String id, String name, String message) {
        return new CapabilityView(id, name, Status.READY, message, null);
    }

    private static CapabilityView actionRequired(String id, String name, String message, String action) {
        return new CapabilityView(id, name, Status.ACTION_REQUIRED, message, action);
    }

    private static CapabilityView degraded(String id, String name, String message, String action) {
        return new CapabilityView(id, name, Status.DEGRADED, message, action);
    }

    private static CapabilityView unavailable(String id, String name, String message, String action) {
        return new CapabilityView(id, name, Status.UNAVAILABLE, message, action);
    }
}
