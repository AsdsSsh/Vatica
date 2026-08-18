package com.example.vatica.agent;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.task.BlackboardEntry;
import com.example.vatica.task.TaskPlan;

/** 迭代 17B：人作为第一等 Agent，只通过可审计 note 和仲裁决定写黑板。 */
@Component
public class HumanAgent {

    public static final int MAX_NOTE_CHARS = 1_000;

    public BlackboardEntry note(TaskPlan plan, RequestIdentity identity, int currentStep, String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("操作失败：人工备注不能为空。");
        }
        if (content.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("操作失败：人工备注不能超过 " + MAX_NOTE_CHARS + " 字。");
        }
        String author = identity.username() == null || identity.username().isBlank()
                ? "HUMAN" : "HUMAN:" + identity.username();
        BlackboardEntry entry = new BlackboardEntry(null, BlackboardEntry.NOTE,
                Math.max(currentStep, 0), "human", author, content, null, List.of(),
                BlackboardEntry.RECORDED, null);
        plan.addBlackboardEntry(entry);
        return entry;
    }
}
