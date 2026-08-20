package com.example.vatica.tool;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.FileSandboxPolicy;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.workspace.WorkspaceStore;
import com.example.vatica.workspace.WorkspaceProperties;
import com.example.vatica.mail.UserMailService;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.knowledge.KnowledgeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.vatica.agentscope.AgentScopeToolProvider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具层装配：AgentScope Toolkit 反射本地 @Tool 方法并生成原生工具目录。
 *
 * <p>AgentScope 注解是本地工具名、描述和 JSON Schema 的唯一事实来源；聊天、任务和 MCP
 * Server 复用同一 {@link AgentToolProvider}，避免三套工具目录漂移。
 */
@Configuration
@EnableConfigurationProperties({FileToolProperties.class, ToolProperties.class, MailProperties.class,
        AppStateProperties.class, WorkspaceProperties.class})
public class ToolConfig {

    /** 迭代 11：文件沙盒策略由工具层显式装配（默认工作区根 = workspace-dir）。 */
    @Bean
    FileSandboxPolicy fileSandboxPolicy(FilePermissionRequestService permissionRequests,
            FileToolProperties fileProps, WorkspaceStore workspaceStore, PermissionPolicyService policyService) {
        return new FileSandboxPolicy(permissionRequests, fileProps, workspaceStore, policyService);
    }

    @Bean
    FileTools fileTools(FileToolProperties props, FileSandboxPolicy sandboxPolicy) {
        return new FileTools(props, sandboxPolicy);
    }

    @Bean
    TextTools textTools() {
        return new TextTools();
    }

    /** 迭代 3：文档生成工具（POI）；迭代 11 起落盘走工作区沙盒。 */
    @Bean
    DocumentTools documentTools(FileSandboxPolicy sandboxPolicy) {
        return new DocumentTools(sandboxPolicy);
    }

    /** 迭代 3.5：PIM 日历工具；迭代 14 起按用户存数据库，导入源走工作区沙盒。 */
    @Bean
    CalendarTools calendarTools(CalendarEventRecordRepository repository, FileSandboxPolicy sandboxPolicy) {
        return new CalendarTools(repository, sandboxPolicy);
    }

    /** 迭代 3.5：PIM 待办工具；迭代 14 起按用户存数据库。 */
    @Bean
    TodoTools todoTools(TodoRecordRepository repository) {
        return new TodoTools(repository);
    }

    /** 迭代 3.5：PIM 邮件工具；迭代 14 起按当前用户解析邮箱设置与凭据。 */
    @Bean
    MailTools mailTools(UserMailService userMailService) {
        return new MailTools(userMailService);
    }

    /** 迭代 11：工作区根查询工具。 */
    @Bean
    WorkspaceTools workspaceTools(FileSandboxPolicy sandboxPolicy) {
        return new WorkspaceTools(sandboxPolicy);
    }

    @Bean
    KnowledgeTools knowledgeTools(KnowledgeBaseService service, ObjectMapper mapper) {
        return new KnowledgeTools(service, mapper);
    }

    /** 迭代 22B：本地工具以 AgentScope 注解为唯一事实源。 */
    @Bean
    AgentToolProvider vaticaTools(FileTools fileTools, TextTools textTools, DocumentTools documentTools,
            CalendarTools calendarTools, TodoTools todoTools, MailTools mailTools, WorkspaceTools workspaceTools,
            KnowledgeTools knowledgeTools, ToolProperties props, ObjectMapper mapper) {
        return new AgentScopeToolProvider(props.maxCallsPerRequest(), mapper, fileTools, textTools, documentTools,
                calendarTools, todoTools, mailTools, workspaceTools, knowledgeTools);
    }
}
