package com.example.vatica.task;

/**
 * 评测结论（迭代 5.5 质量闭环）：Judge Agent 评分后由阈值判定的最终去向。
 *
 * <p>PASS = 评分达标（≥ pass-threshold）→ 交付 DONE；FAIL = 评分不达标 →
 * 自动返工（限 max-auto-rework 次）或超限转 NEEDS_REVISION 交人工。
 */
public enum TaskVerdict {
    PASS,
    FAIL
}
