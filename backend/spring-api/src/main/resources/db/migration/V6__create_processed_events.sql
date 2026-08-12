-- Cross-service Kafka idempotency (.claude/rules/architecture.md). Every consumer
-- — Java or Python — inserts its (event_id, consumer_group) row in the same
-- transaction as the side effect it performs. Postgres is the idempotency
-- authority; Redis may front this as a fast-path check but never replaces it.
CREATE TABLE processed_events (
    event_id       UUID NOT NULL,
    consumer_group VARCHAR(200) NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_group)
);
