package com.example.vatica.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 迭代 18B：SSE/权限频道必须把用户维度放在资源标识之前，防止同名任务串流。 */
class TenantChannelsTest {

    @Test
    void taskChannelsSeparateUsersEvenWhenTaskIdsMatch() {
        String left = TenantChannels.task(new RequestIdentity(1L, 10L, "LOCAL", "left"), "same-task");
        String right = TenantChannels.task(new RequestIdentity(2L, 10L, "LOCAL", "right"), "same-task");

        assertThat(left).isEqualTo("user:1:task:same-task");
        assertThat(right).isEqualTo("user:2:task:same-task");
        assertThat(left).isNotEqualTo(right);
    }
}
