package com.example.vatica.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;

/** 迭代 15 I15-5：401/超时触发角色偏移，后续 prompt 自动切到备用槽位客户端。 */
class RoleFailoverChatClientTest {

    @Test
    void authFailureAdvancesOffsetAndNextPromptUsesBackup() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient backup = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec primarySpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.ChatClientRequestSpec backupSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec primaryCall = mock(ChatClient.CallResponseSpec.class);
        ChatClient.CallResponseSpec backupCall = mock(ChatClient.CallResponseSpec.class);

        when(primary.prompt("hi")).thenReturn(primarySpec);
        when(primarySpec.call()).thenReturn(primaryCall);
        when(primaryCall.content()).thenThrow(new RuntimeException("401 Unauthorized"));
        when(backup.prompt("hi")).thenReturn(backupSpec);
        when(backupSpec.call()).thenReturn(backupCall);
        when(backupCall.content()).thenReturn("备用模型回复");

        AtomicInteger offset = new AtomicInteger();
        ChatClient client = new RoleFailoverChatClient(
                () -> offset.get() == 0 ? primary : backup,
                e -> e.getMessage() != null && e.getMessage().contains("401"),
                offset::incrementAndGet);

        assertThatThrownBy(() -> client.prompt("hi").call().content())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("401");
        assertThat(offset.get()).isEqualTo(1);

        assertThat(client.prompt("hi").call().content()).isEqualTo("备用模型回复");
    }

    @Test
    void nonAuthErrorDoesNotAdvanceOffset() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(primary.prompt("hi")).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenThrow(new IllegalArgumentException("业务校验失败"));

        AtomicInteger offset = new AtomicInteger();
        ChatClient client = new RoleFailoverChatClient(() -> primary,
                e -> e.getMessage() != null && e.getMessage().contains("401"), offset::incrementAndGet);

        assertThatThrownBy(() -> client.prompt("hi").call().content())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(offset.get()).isZero();
    }
}
