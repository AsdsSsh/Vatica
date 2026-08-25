package com.example.vatica.context;

import java.util.ArrayList;
import java.util.List;

import com.example.vatica.controller.SessionMemory;
import com.example.vatica.controller.SessionMemory.ContextWindow;
import com.example.vatica.model.ConversationMessage;

/**
 * 迭代 15 I15-9 / 29A：三层会话记忆组装——成功摘要、未摘要区间的受控头尾、近期原文，
 * 再按调用点 token 预算兜底裁剪。
 */
public final class ContextAssembler {

    private static final int FALLBACK_EXCERPT_CHARS = 600;

    private ContextAssembler() {
    }

    public static List<ConversationMessage> chatHistory(SessionMemory memory, String sessionId, ContextBudget budget) {
        ContextWindow window = memory.contextWindow(sessionId);
        List<ConversationMessage> combined = new ArrayList<>(
                window.recent().size() + window.uncoveredHead().size() + 3);
        List<Boolean> mandatory = new ArrayList<>();
        if (window.summary() != null && !window.summary().isBlank()) {
            combined.add(ConversationMessage.user("【历史会话摘要】\n" + window.summary()));
            mandatory.add(window.hasFallbackHistory());
        }
        if (window.hasFallbackHistory()) {
            for (int i = 0; i < window.uncoveredHead().size(); i++) {
                combined.add(fallbackExcerpt(window.uncoveredHead().get(i)));
                // 至少保留未摘要区间头部的第一条证据。
                mandatory.add(i == 0);
            }
            if (!window.uncoveredTail().isEmpty()) {
                combined.add(ConversationMessage.user("【未摘要历史中段省略】以下为该区间末尾片段。"));
                mandatory.add(false);
                for (int i = 0; i < window.uncoveredTail().size(); i++) {
                    combined.add(fallbackExcerpt(window.uncoveredTail().get(i)));
                    // 至少保留未摘要区间尾部的最后一条证据。
                    mandatory.add(i == window.uncoveredTail().size() - 1);
                }
            }
            combined.add(ConversationMessage.user("【未摘要历史降级边界】摘要状态="
                    + window.summaryStatus() + "；已覆盖至消息 #" + window.summaryThroughSeq()
                    + "；已请求覆盖至消息 #" + window.summaryRequestedThroughSeq()
                    + "；仍有 " + window.uncoveredMessageCount()
                    + " 条原始消息未被摘要。以上仅为受控头尾片段，以下为近期对话；"
                    + "未列出的内容不得视为已验证事实。"));
            mandatory.add(true);
        }
        for (int i = 0; i < window.recent().size(); i++) {
            combined.add(window.recent().get(i));
            // 降级路径至少保留近期尾部；健康路径交给原有尾部保护逻辑。
            mandatory.add(window.hasFallbackHistory() && i == window.recent().size() - 1);
        }
        if (!window.hasFallbackHistory()) {
            return ContextTrimmer.trim(combined, budget.tokensFor(ContextBudget.CallSite.CHAT), 1);
        }
        return trimDegraded(combined, mandatory, budget.tokensFor(ContextBudget.CallSite.CHAT));
    }

    /**
     * 降级上下文不能把摘要和“这里有未覆盖区间”的边界标记从最旧端一起裁掉。
     * 只淘汰非关键片段；若关键片段本身就超预算，则沿用完整消息优先的语义返回它们。
     */
    private static List<ConversationMessage> trimDegraded(List<ConversationMessage> messages,
            List<Boolean> mandatory, int tokenBudget) {
        List<ConversationMessage> working = new ArrayList<>(messages);
        List<Boolean> required = new ArrayList<>(mandatory);
        while (working.size() > 1 && ContextTrimmer.estimateTokens(working) > tokenBudget) {
            int remove = -1;
            for (int i = 0; i < required.size(); i++) {
                if (!required.get(i)) {
                    remove = i;
                    break;
                }
            }
            if (remove < 0) {
                break;
            }
            working.remove(remove);
            required.remove(remove);
        }
        return List.copyOf(working);
    }

    private static ConversationMessage fallbackExcerpt(ConversationMessage message) {
        String text = message.text() == null ? "" : message.text();
        if (text.length() <= FALLBACK_EXCERPT_CHARS) {
            return message;
        }
        int half = FALLBACK_EXCERPT_CHARS / 2;
        String excerpt = text.substring(0, half) + "\n…（未摘要原文中段省略）…\n"
                + text.substring(text.length() - half);
        return message.role() == ConversationMessage.Role.USER
                ? ConversationMessage.user(excerpt) : ConversationMessage.assistant(excerpt);
    }
}
