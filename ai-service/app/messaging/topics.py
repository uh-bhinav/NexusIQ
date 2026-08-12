"""Mirrors Java's messaging/KafkaTopics.java — Java owns topic creation
(broker auto-create is disabled); Python only produces/consumes by name.
"""

DOCUMENT_UPLOADED = "document.uploaded"
DOCUMENT_PROCESSED = "document.processed"
DOCUMENT_FAILED = "document.failed"

DECISION_REQUESTED = "decision.requested"
DECISION_PROGRESS = "decision.progress"
DECISION_COMPLETED = "decision.completed"
DECISION_FAILED = "decision.failed"

APPROVAL_COMPLETED = "approval.completed"


def dlq(topic: str) -> str:
    return f"{topic}.dlq"
