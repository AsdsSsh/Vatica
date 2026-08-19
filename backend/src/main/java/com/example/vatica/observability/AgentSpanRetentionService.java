package com.example.vatica.observability;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 启动时按留存期清理过期 Span；失败只告警，不阻止 Agent 服务启动。 */
@Service
public class AgentSpanRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AgentSpanRetentionService.class);

    private final AgentSpanRecordRepository repository;
    private final ObservabilityProperties properties;

    public AgentSpanRetentionService(AgentSpanRecordRepository repository, ObservabilityProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void cleanup() {
        try {
            long removed = repository.deleteByStartedAtBefore(Instant.now().minus(properties.retention()));
            if (removed > 0) {
                log.info("Agent Span 留存清理完成：removed={} retention={}", removed, properties.retention());
            }
        } catch (RuntimeException e) {
            log.warn("Agent Span 留存清理失败，不影响业务启动", e);
        }
    }
}
