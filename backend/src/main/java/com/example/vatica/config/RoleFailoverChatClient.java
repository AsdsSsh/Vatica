package com.example.vatica.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * 角色故障转移 ChatClient（迭代 15 I15-5）：
 * prompt() 每次按注册表当前偏移取主槽位；当 {@code call()/stream()} 返回的响应
 * 抛出 401/超时等可转移错误时，通知注册表把该角色的偏移推进到下一个同能力槽位，
 * 后续请求自动切到备用模型。同一请求不重放（避免副作用步骤被模型重试两次）。
 */
public final class RoleFailoverChatClient implements ChatClient {

    private final Supplier<ChatClient> primary;
    private final Predicate<RuntimeException> failoverTrigger;
    private final Runnable onFailover;

    public RoleFailoverChatClient(Supplier<ChatClient> primary,
            Predicate<RuntimeException> failoverTrigger, Runnable onFailover) {
        this.primary = primary;
        this.failoverTrigger = failoverTrigger;
        this.onFailover = onFailover;
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return spec(primary.get().prompt());
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return spec(primary.get().prompt(content));
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        return spec(primary.get().prompt(prompt));
    }

    @Override
    public Builder mutate() {
        return primary.get().mutate();
    }

    private void handleFailure(Throwable error) {
        if (error instanceof RuntimeException runtime && failoverTrigger.test(runtime)) {
            onFailover.run();
        }
    }

    private ChatClientRequestSpec spec(ChatClientRequestSpec delegate) {
        return (ChatClientRequestSpec) Proxy.newProxyInstance(
                ChatClientRequestSpec.class.getClassLoader(),
                new Class<?>[] { ChatClientRequestSpec.class },
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) {
                        return method.invoke(delegate, args);
                    }
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        handleFailure(e.getCause());
                        throw e.getCause();
                    }
                    if (result instanceof ChatClientRequestSpec requestSpec) {
                        return spec(requestSpec);
                    }
                    if (result instanceof CallResponseSpec callSpec) {
                        return callSpec(callSpec);
                    }
                    if (result instanceof StreamResponseSpec streamSpec) {
                        return streamSpec(streamSpec);
                    }
                    return result;
                });
    }

    private CallResponseSpec callSpec(CallResponseSpec delegate) {
        return (CallResponseSpec) Proxy.newProxyInstance(
                CallResponseSpec.class.getClassLoader(),
                new Class<?>[] { CallResponseSpec.class },
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) {
                        return method.invoke(delegate, args);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        handleFailure(e.getCause());
                        throw e.getCause();
                    }
                });
    }

    private StreamResponseSpec streamSpec(StreamResponseSpec delegate) {
        return (StreamResponseSpec) Proxy.newProxyInstance(
                StreamResponseSpec.class.getClassLoader(),
                new Class<?>[] { StreamResponseSpec.class },
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) {
                        return method.invoke(delegate, args);
                    }
                    try {
                        Object result = method.invoke(delegate, args);
                        if (result instanceof Flux<?> flux) {
                            return Flux.from(flux).doOnError(this::handleFailure);
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        handleFailure(e.getCause());
                        throw e.getCause();
                    }
                });
    }

    private static boolean isObjectMethod(Method method) {
        return switch (method.getName()) {
            case "toString", "hashCode", "equals" -> true;
            default -> false;
        };
    }
}
