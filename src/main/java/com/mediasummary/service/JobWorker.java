package com.mediasummary.service;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class JobWorker {

    private final JobQueueService queueService;
    private final JobService jobService;

    @Qualifier("taskExecutor")
    private final Executor taskExecutor;

    @Value("${app.worker.batch-size:3}")
    private int batchSize;

    @Value("${app.worker.queue-block-ms:1500}")
    private long queueBlockMs;

    @Scheduled(fixedDelayString = "${app.worker.poll-ms:1000}")
    public void pollQueue() {
        int count = Math.max(1, batchSize);
        for (int i = 0; i < count; i++) {
            JobQueueService.QueueMessage message = queueService.readNext(Duration.ofMillis(queueBlockMs));
            if (message == null) {
                return;
            }

            taskExecutor.execute(() -> process(message));
        }
    }

    private void process(JobQueueService.QueueMessage message) {
        try {
            jobService.processJob(message.getJobId());
        } catch (Exception e) {
            log.error("Unexpected worker failure for job {}", message.getJobId(), e);
        } finally {
            queueService.acknowledge(message.getRecordId());
        }
    }
}

