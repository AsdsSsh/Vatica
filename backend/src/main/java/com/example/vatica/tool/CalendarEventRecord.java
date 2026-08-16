package com.example.vatica.tool;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import com.example.vatica.tool.IcsParser.CalendarEvent;

/** 迭代 14：按用户隔离的日程持久化行。 */
@Entity
@Table(name = "vatica_event", indexes = {
        @Index(name = "idx_event_owner_start", columnList = "userId,startAt") })
public class CalendarEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Column(length = 100)
    private String recurrenceRule;

    protected CalendarEventRecord() { }

    public CalendarEventRecord(Long userId, Long orgId, CalendarEvent event) {
        this.userId = userId;
        this.orgId = orgId;
        this.summary = event.summary();
        this.startAt = event.start();
        this.endAt = event.end();
        this.recurrenceRule = event.rrule() == null ? null
                : event.rrule().toIcs().substring("RRULE:".length());
    }

    public CalendarEvent toEvent() {
        return new CalendarEvent(summary, startAt, endAt,
                recurrenceRule == null ? null : IcsParser.parseRrule(recurrenceRule));
    }

    public Long getId() { return id; }
}
