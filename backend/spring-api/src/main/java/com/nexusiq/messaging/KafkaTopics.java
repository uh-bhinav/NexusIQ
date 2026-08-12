package com.nexusiq.messaging;

/** Topic name constants (.claude/rules/architecture.md). One DLQ per consumed topic. */
public final class KafkaTopics {

    public static final String DOCUMENT_UPLOADED = "document.uploaded";
    public static final String DOCUMENT_PROCESSED = "document.processed";
    public static final String DOCUMENT_FAILED = "document.failed";

    public static final String DECISION_REQUESTED = "decision.requested";
    public static final String DECISION_PROGRESS = "decision.progress";
    public static final String DECISION_COMPLETED = "decision.completed";
    public static final String DECISION_FAILED = "decision.failed";

    // approval.requested is deliberately not a Kafka topic: the `approvals`
    // table row + its audit_events entry (written in the same transaction
    // that creates it) already make "a decision is awaiting approval" both
    // durable and queryable, and nothing in this codebase consumes such an
    // event yet — adding a topic with zero consumers has no engineering
    // justification (CLAUDE.md non-negotiable #12). Recorded in STATUS.md.
    public static final String APPROVAL_COMPLETED = "approval.completed";

    private KafkaTopics() {}

    public static String dlq(String topic) {
        return topic + ".dlq";
    }
}
