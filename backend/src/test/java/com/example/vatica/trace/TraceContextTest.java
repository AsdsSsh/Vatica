package com.example.vatica.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 迭代 15 I15-1：trace 上下文快照隔离与恢复（异步线程不串 trace）。 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    void callWithRestoresPreviousSnapshot() {
        TraceContext.Snapshot outer = new TraceContext.Snapshot("t1", "ch1", null, null, 1L, 1L, false);
        TraceContext.Snapshot inner = new TraceContext.Snapshot("t2", "ch2", "task", 2, 1L, 1L, true);
        TraceContext.set(outer);

        String seen = TraceContext.callWith(inner, () -> TraceContext.current().traceId());
        String after = TraceContext.current().traceId();

        assertThat(seen).isEqualTo("t2");
        assertThat(after).isEqualTo("t1");
    }

    @Test
    void clearRemovesSnapshot() {
        TraceContext.set(new TraceContext.Snapshot("t1", "ch1", null, null, 1L, 1L, false));
        TraceContext.clear();

        assertThat(TraceContext.current()).isNull();
    }
}
