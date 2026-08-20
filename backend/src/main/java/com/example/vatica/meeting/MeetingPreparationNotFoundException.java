package com.example.vatica.meeting;

/** 当前用户不可见或不存在的会议准备统一按资源不存在处理。 */
public class MeetingPreparationNotFoundException extends RuntimeException {
    public MeetingPreparationNotFoundException(String id) {
        super("会议准备不存在或无权访问：" + id);
    }
}
