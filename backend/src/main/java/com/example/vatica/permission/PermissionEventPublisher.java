package com.example.vatica.permission;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.vatica.event.SseEventGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 文件权限请求事件入口（迭代 11；迭代 16 I16-2）：权限事件统一交给
 * {@link SseEventGateway}，不再维护第二套订阅者注册表。
 */
@Component
public class PermissionEventPublisher {

    private final SseEventGateway gateway;

    @Autowired
    public PermissionEventPublisher(SseEventGateway gateway) {
        this.gateway = gateway;
    }

    /** 测试/兼容构造器；生产装配使用统一网关 Bean。 */
    public PermissionEventPublisher(ObjectMapper mapper) {
        this(new SseEventGateway(mapper));
    }

    /** 推送权限请求；返回是否有订阅者收到（无订阅者时调用方应立即按拒绝处理）。 */
    public boolean publish(FilePermissionRequest request) {
        return gateway.publishTransient(request.channel(), "permission_request", request);
    }
}
