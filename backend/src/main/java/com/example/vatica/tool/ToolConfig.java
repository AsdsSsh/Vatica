package com.example.vatica.tool;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具层装配：显式注册 {@link ToolCallbackProvider} Bean。
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
@EnableConfigurationProperties(FileToolProperties.class)
public class ToolConfig {

    @Bean
    FileTools fileTools(FileToolProperties props) {
        return new FileTools(props);
    }

    @Bean
    TextTools textTools() {
        return new TextTools();
    }

    /** 把 @Tool 注解方法自动生成为 ToolCallback（任务清单 I2-2 的"ToolCallback Bean 显式注册"）。 */
    @Bean
    ToolCallbackProvider vaticaTools(FileTools fileTools, TextTools textTools) {
        return MethodToolCallbackProvider.builder().toolObjects(fileTools, textTools).build();
    }
}
