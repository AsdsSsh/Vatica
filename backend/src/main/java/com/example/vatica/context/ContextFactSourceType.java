package com.example.vatica.context;

/** 迭代 29B：关键事实的可追溯来源类型。 */
public enum ContextFactSourceType {
    USER_INPUT,
    CHAT_MESSAGE,
    TASK,
    TASK_STEP,
    ACTION_EXECUTION,
    ARTIFACT,
    CALENDAR_EVENT,
    TODO,
    KNOWLEDGE_CITATION,
    SYSTEM
}
