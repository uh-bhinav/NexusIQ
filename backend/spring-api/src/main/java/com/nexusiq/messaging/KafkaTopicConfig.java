package com.nexusiq.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The broker has auto-topic-creation disabled (docker-compose.yml
 * KAFKA_AUTO_CREATE_TOPICS_ENABLE=false), so every topic this service produces
 * or consumes is declared explicitly here; Spring's {@code KafkaAdmin} creates
 * them on startup. Java is the single declared owner of topic topology, the
 * same way Flyway is the single owner of the relational schema.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic documentUploadedTopic() {
        return topic(KafkaTopics.DOCUMENT_UPLOADED);
    }

    @Bean
    public NewTopic documentUploadedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DOCUMENT_UPLOADED));
    }

    @Bean
    public NewTopic documentProcessedTopic() {
        return topic(KafkaTopics.DOCUMENT_PROCESSED);
    }

    @Bean
    public NewTopic documentProcessedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DOCUMENT_PROCESSED));
    }

    @Bean
    public NewTopic documentFailedTopic() {
        return topic(KafkaTopics.DOCUMENT_FAILED);
    }

    @Bean
    public NewTopic documentFailedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DOCUMENT_FAILED));
    }

    // decision.requested is produced by Java, consumed by Python — same
    // shape as document.uploaded (produced by Java, consumed by Python) and
    // document.processed/.failed (produced by Python, consumed by Java):
    // whoever consumes routes failures to the DLQ, but Java always declares
    // the topic regardless of which side produces or consumes it.
    @Bean
    public NewTopic decisionRequestedTopic() {
        return topic(KafkaTopics.DECISION_REQUESTED);
    }

    @Bean
    public NewTopic decisionRequestedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DECISION_REQUESTED));
    }

    @Bean
    public NewTopic decisionProgressTopic() {
        return topic(KafkaTopics.DECISION_PROGRESS);
    }

    @Bean
    public NewTopic decisionProgressDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DECISION_PROGRESS));
    }

    @Bean
    public NewTopic decisionCompletedTopic() {
        return topic(KafkaTopics.DECISION_COMPLETED);
    }

    @Bean
    public NewTopic decisionCompletedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DECISION_COMPLETED));
    }

    @Bean
    public NewTopic decisionFailedTopic() {
        return topic(KafkaTopics.DECISION_FAILED);
    }

    @Bean
    public NewTopic decisionFailedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.DECISION_FAILED));
    }

    // approval.completed is produced by Java, consumed by Python (resumes the
    // interrupted LangGraph run) — same "Java always declares the topic
    // regardless of which side produces or consumes it" convention as every
    // other topic here.
    @Bean
    public NewTopic approvalCompletedTopic() {
        return topic(KafkaTopics.APPROVAL_COMPLETED);
    }

    @Bean
    public NewTopic approvalCompletedDlqTopic() {
        return topic(KafkaTopics.dlq(KafkaTopics.APPROVAL_COMPLETED));
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }
}
