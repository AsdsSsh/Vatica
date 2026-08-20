package com.example.vatica.meeting;

/** 迭代 24：会议准备从草案到受控写入的生命周期。 */
public enum MeetingPreparationStatus {
    /** 仅包含经过用户确认的会议事实和预览，不产生任何副作用。 */
    DRAFT,
    /** 用户拒绝草案；文件和待办均不会写入。 */
    REJECTED,
    /** 已按批准后的差异写入准备文档与待办。 */
    APPLIED,
    /** 写入阶段失败；保留草案、失败原因和已知产物位置供人工处理。 */
    FAILED
}
