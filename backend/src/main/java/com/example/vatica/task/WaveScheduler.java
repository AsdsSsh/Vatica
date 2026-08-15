package com.example.vatica.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.example.vatica.task.TaskPlan.TaskStep;

/**
 * 并行波次调度（迭代 6 I6-1）：把步骤计划按依赖拓扑分层为"波"——同一波内的步骤互不依赖、
 * 可并行执行；波与波之间保持依赖顺序。
 *
 * <p>规则：
 * <ul>
 *   <li>步骤层级 = 1 + max(依赖步骤层级)，默认顺序依赖（依赖上一步）即每个步骤独占一波，
 *       行为与迭代 5 顺序执行完全一致（向后兼容）</li>
 *   <li>依赖引用越界/自引用等非法值忽略（Planner 归一化已保证，此处双保险）</li>
 *   <li><b>审批点屏障</b>：needsApproval 且未批准的步骤独占一波——审批是执行前屏障，
 *       并行波不允许绕过人工确认</li>
 * </ul>
 */
public final class WaveScheduler {

    private WaveScheduler() {
    }

    /**
     * 计算执行波次。返回每波包含的步骤下标（0 起，按计划顺序）；确定性函数：
     * 同一计划多次计算结果一致，因此审批续批后重算的波次边界与挂起前一致。
     */
    public static List<List<Integer>> waves(TaskPlan plan) {
        List<TaskStep> steps = plan.getSteps();
        int n = steps.size();
        int[] level = new int[n];
        for (int i = 0; i < n; i++) {
            int lvl = 0;
            List<Integer> deps = steps.get(i).getDependsOn();
            if (deps == null) {
                // 老数据（迭代 6 前落库的计划无 dependsOn 字段）→ 顺序执行（依赖上一步），向后兼容
                lvl = i == 0 ? 0 : level[i - 1] + 1;
            } else {
                for (int dep : deps) {
                    if (dep >= 1 && dep <= n && dep - 1 < i) {
                        lvl = Math.max(lvl, level[dep - 1] + 1);
                    }
                }
            }
            level[i] = lvl;
        }

        Map<Integer, List<Integer>> byLevel = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            byLevel.computeIfAbsent(level[i], k -> new ArrayList<>()).add(i);
        }

        List<List<Integer>> result = new ArrayList<>();
        for (List<Integer> group : byLevel.values()) {
            List<Integer> current = new ArrayList<>();
            for (int idx : group) {
                TaskStep step = steps.get(idx);
                if (step.isNeedsApproval() && !step.isApproved()) {
                    // 审批点屏障：flush 当前波、审批步骤独占一波
                    if (!current.isEmpty()) {
                        result.add(List.copyOf(current));
                        current.clear();
                    }
                    result.add(List.of(idx));
                } else {
                    current.add(idx);
                }
            }
            if (!current.isEmpty()) {
                result.add(List.copyOf(current));
            }
        }
        return result;
    }
}
