package com.example.vatica.model;

import reactor.core.publisher.Flux;

/** 迭代 22A：业务层唯一模型入口；实现由 AgentScope 提供。 */
public interface ModelGateway {

    ModelResponse call(ModelInvocation invocation);

    Flux<ModelStreamEvent> stream(ModelInvocation invocation);
}
