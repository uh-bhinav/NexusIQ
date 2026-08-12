package com.nexusiq.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Shared retry/DLQ policy for every {@code @KafkaListener} in this service
 * (.claude/rules/architecture.md: "3 attempts, exponential backoff (1s, 4s,
 * 16s) with jitter, then DLQ"). Spring Boot auto-wires the single
 * {@link DefaultErrorHandler} bean into its auto-configured listener container
 * factory, so no custom factory bean is needed. Blocking, in-process retry —
 * not Spring Kafka's non-blocking @RetryableTopic — because that feature
 * fans out into several retry-N topics per listener, which does not match
 * "one DLQ per consumed topic".
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final long INITIAL_INTERVAL_MS = 1000L;
    private static final double MULTIPLIER = 4.0;
    private static final long MAX_INTERVAL_MS = 16000L;
    /** 1 initial attempt + 2 retries = "3 attempts" (architecture.md, testing.md scenario 9). */
    private static final int MAX_RETRIES = 2;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.dlq(record.topic()), -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
