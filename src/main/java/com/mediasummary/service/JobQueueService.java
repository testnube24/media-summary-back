package com.mediasummary.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobQueueService {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.queue.stream-key:audio:jobs}")
    private String streamKey;

    @Value("${app.queue.consumer-group:audio-workers}")
    private String consumerGroup;

    @Value("${app.queue.consumer-name:${HOSTNAME:worker-1}}")
    private String consumerName;

    @PostConstruct
    public void ensureGroup() {
        try {
            StreamOperations<String, Object, Object> ops = redisTemplate.opsForStream();
            ops.createGroup(streamKey, ReadOffset.latest(), consumerGroup);
            log.info("Created redis stream group {} on stream {}", consumerGroup, streamKey);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("requires the key to exist")) {
                redisTemplate.opsForStream().add(streamKey, Collections.singletonMap("bootstrap", "1"));
                try {
                    redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
                } catch (Exception ignored) {
                    // ignore if already exists after race.
                }
            } else {
                // Usually means group already exists.
                log.info("Redis stream group already exists or could not be created now: {}", msg);
            }
        }
    }

    public String enqueueJob(Long jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId cannot be null");
        Map<String, String> payload = Collections.singletonMap("jobId", jobId.toString());
        RecordId id = redisTemplate.opsForStream().add(streamKey, payload);
        return id != null ? id.getValue() : null;
    }

    public QueueMessage readNext(Duration blockFor) {
        try {
            StreamReadOptions options = StreamReadOptions.empty().count(1);
            if (blockFor != null && !blockFor.isNegative() && !blockFor.isZero()) {
                options = options.block(blockFor);
            }

            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    options,
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                return null;
            }

            MapRecord<String, Object, Object> record = records.get(0);
            Object jobIdVal = record.getValue().get("jobId");
            if (jobIdVal == null) {
                acknowledge(record.getId().getValue());
                return null;
            }
            Long jobId = Long.parseLong(jobIdVal.toString());
            return new QueueMessage(record.getId().getValue(), jobId);
        } catch (Exception e) {
            log.error("Error reading queue message", e);
            return null;
        }
    }

    public void acknowledge(String recordId) {
        if (recordId == null || recordId.isBlank()) return;
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, RecordId.of(recordId));
    }

    @lombok.Value
    public static class QueueMessage {
        String recordId;
        Long jobId;
    }
}
