package com.example.vatica.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 18B：固定评测目录不能成为未登录的公共配置出口。 */
class EvaluationControllerTest {

    private final EvaluationController controller = new EvaluationController(new BenchmarkCatalog());

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void returnsCatalogForAuthenticatedUser() {
        RequestIdentityContext.set(new RequestIdentity(7L, 9L, "LOCAL", "tester"));

        assertThat(controller.benchmarkCases()).hasSize(4);
    }

    @Test
    void rejectsAnonymousCatalogAccess() {
        assertThatThrownBy(controller::benchmarkCases)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少用户身份");
    }
}
