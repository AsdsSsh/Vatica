package com.example.vatica.tool;

import com.example.vatica.config.AppStateProperties;
import com.example.vatica.permission.FilePermissionRequestService;
import com.example.vatica.permission.FileSandboxPolicy;
import com.example.vatica.permission.PermissionPolicyService;
import com.example.vatica.workspace.WorkspaceStore;
import com.example.vatica.workspace.WorkspaceProperties;
import com.example.vatica.mail.UserMailService;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具层装配：显式注册 {@link ToolCallbackProvider} Bean（迭代 2.5 加工具调用次数护栏）。
 *
 * <p>两层机制（面试可讲）：
 * <ol>
 *   <li><b>模型侧工具定义</b>：模型能看到哪些工具，取决于请求选项里的 tool callbacks
 *       （ChatController 的 {@code defaultTools(...)} 把本 Provider 喂给每个请求）</li>
 *   <li><b>执行期兜底</b>：Spring AI 自动收集的 ToolCallback Bean/Provider Bean 构成 resolver，
 *       工具执行期按名匹配不到时兜底（MCP 时代本地工具与 MCP 工具混用的执行入口）</li>
 * </ol>
 * 两者缺一不可：defaultTools 喂定义，Bean 收集留兜底。
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

    /** 把 @Tool 注解方法自动生成为 ToolCallback（任务清单 I2-2 的"ToolCallback Bean 显式注册"）。 */
    @Bean
    ToolCallbackProvider vaticaTools(FileTools fileTools, TextTools textTools, DocumentTools documentTools,
            CalendarTools calendarTools, TodoTools todoTools, MailTools mailTools, WorkspaceTools workspaceTools,
            ToolProperties props) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(fileTools, textTools, documentTools, calendarTools, todoTools, mailTools, workspaceTools)
                .build();
        return new ToolCallLimitProvider(provider, props.maxCallsPerRequest());
    }
}
