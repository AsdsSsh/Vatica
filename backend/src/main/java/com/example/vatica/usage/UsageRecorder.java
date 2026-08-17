package com.example.vatica.usage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 迭代 15 I15-13：用量异步写入——有界队列（4096）+ 单消费者批量 insert。
 * 队列满丢 usage 并自增 droppedCounter：观测数据可丢，业务不因观测挂。
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);
    private static final int QUEUE_CAPACITY = 4_096;
    private static final int BATCH_SIZE = 100;

    private final UsageRecordRepository repository;
    private final LinkedBlockingQueue<UsageRecord> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread consumer;

    public UsageRecorder(UsageRecordRepository repository) {
        this.repository = repository;
        this.consumer = Thread.ofVirtual().name("usage-recorder").start(this::drain);
    }

    public void enqueue(UsageRecord record) {
        if (!queue.offer(record)) {
            dropped.incrementAndGet();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void drain() {
        List<UsageRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                batch.clear();
                UsageRecord first = queue.take();
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                repository.saveAll(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 单批失败丢弃该批（观测可丢），继续消费；不阻塞业务
                log.warn("usage 批量写库失败，丢弃本批 {}", batch.size(), e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        consumer.interrupt();
    }
}
