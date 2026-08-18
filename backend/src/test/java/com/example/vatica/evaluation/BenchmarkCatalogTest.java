package com.example.vatica.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

/** 迭代 18B：固定任务集必须稳定、唯一且覆盖无副作用/有副作用/审批/越权拒绝。 */
class BenchmarkCatalogTest {

    private final BenchmarkCatalog catalog = new BenchmarkCatalog();

    @Test
    void catalogHasStableCoverageAndUniqueIds() {
        assertThat(catalog.cases()).hasSize(4);
        assertThat(catalog.cases().stream().map(BenchmarkCase::id).toList())
                .doesNotHaveDuplicates();
        assertThat(new HashSet<>(catalog.cases().stream().flatMap(item -> item.requiredTools().stream()).toList()))
                .contains("read_file", "create_word_report", "calendar_query", "todo_add");
        assertThat(catalog.find("weekly-report")).isPresent()
                .get().extracting(BenchmarkCase::hasSideEffect).isEqualTo(true);
        assertThat(catalog.find("permission-boundary")).isPresent()
                .get().extracting(BenchmarkCase::hasSideEffect).isEqualTo(false);
    }
}
