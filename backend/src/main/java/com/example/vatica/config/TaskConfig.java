package com.example.vatica.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.example.vatica.context.ContextBudget;
import com.example.vatica.task.TaskBlackboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 任务执行层装配（迭代 6 I6-1）：虚拟线程执行器——多 Agent 并行执行子任务。
 *
 * <p>Java 21 虚拟线程（JEP 444）：每波并行步骤一个虚拟线程，百万级线程开销近乎为零，
 * 阻塞型 LLM 调用场景下吞吐与线程占用解耦（面试可讲）。
 */
@Configuration
public class TaskConfig {

    @Bean(name = "taskParallelExecutor")
    Executor taskParallelExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 迭代 15 I15-11：任务黑板（dependsOn 最小上下文 + 滚动笔记 + 结果摘要）。 */
    @Bean
    TaskBlackboard taskBlackboard(ModelRegistry registry, ContextBudget contextBudget) {
        return new TaskBlackboard(registry, contextBudget);
    }
}
