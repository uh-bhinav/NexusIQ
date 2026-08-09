# Observability

Rationale: ADR-007. Implemented in Phase 8, but **instrumented from Phase 1 onward** — a feature
without telemetry is not done.

---

## Correlation

One id follows a decision through the entire system:

```
Browser
  └─ X-Correlation-Id ──> spring-api (MDC)
                            └─ Kafka envelope.correlation_id  ← explicit; automatic
                                 └─ ai-service context           propagation does not
                                      └─ LangGraph node spans      cross a broker
                                           └─ LLM call spans
                            └─ response body request_id
```

Minted at the API edge if absent. Present in every log line, every event, every span, every error
response. This is the single most useful debugging affordance in the system.

## Traces

One trace per decision, spanning: HTTP request → Kafka publish → Kafka consume → LangGraph run →
each node → each retrieval → each LLM call → completion event → SSE emission.

Span attributes:

| Span | Attributes |
|---|---|
| HTTP | method, route, status, user_id, workspace_id |
| Kafka | topic, partition, offset, event_type, event_id |
| Graph run | decision_id, workflow_version, prompt_version, iteration |
| Node | agent_name, status, iteration |
| Retrieval | domain, top_k, result_count, max_similarity, rerank_enabled |
| LLM call | model, input_tokens, output_tokens, estimated_cost_usd, finish_reason, repaired |
| DB | operation, table (via auto-instrumentation) |

**Never** put secrets, document text, prompt bodies or PII in span attributes.

## Metrics

**Infrastructure** — `http_request_duration` (route, status), `http_request_count`,
`http_error_count`, `db_query_duration`, `redis_hit_ratio`, `kafka_consumer_lag`,
`kafka_processing_duration`, `dlq_message_count`.

**RAG** — `retrieval_duration` (stage), `retrieval_result_count`, `retrieval_similarity`
(histogram), `retrieval_empty_count`, `rerank_duration`, `embedding_duration`.

**AI** — `agent_duration` (agent_name), `agent_failure_rate`, `llm_tokens_total`
(model, direction), `llm_cost_usd_total` (model), `llm_error_count` (type),
`decision_confidence` (histogram), `validation_failure_rate` (check), `schema_repair_rate`,
`budget_exceeded_count`, `injection_detected_count`.

**Business** — `decisions_processed_total` (status), `decisions_by_recommendation`,
`human_escalation_rate` (+ reason), `approval_turnaround_seconds`, `decision_duration_seconds`.

The AI and business groups are the ones that make this a production AI system rather than an
instrumented web app. Cost per decision and escalation rate are operational facts, not curiosities.

## Logs

Structured JSON, both services. Every line: `timestamp`, `level`, `service`, `correlation_id`,
`message`, plus context. `decision_id` and `workspace_id` wherever they apply.

Never log: passwords, JWTs, `LLM_API_KEY` (or any secret), full document text, full prompt bodies
at info level, PII beyond a user id.

Levels: `ERROR` needs human attention · `WARN` degraded but handled (cache miss storm, retry, DLQ
routing) · `INFO` significant lifecycle events · `DEBUG` local only, may include truncated prompts.

## Stack

All local, all free (ADR-010): OTel SDK/agent in each service → OTel Collector → Jaeger/Tempo
(traces) + Prometheus (metrics) → Grafana (dashboards).

## Dashboard

One Grafana dashboard, four rows:

1. **Business** — decisions processed, recommendation split, escalation rate, approval turnaround.
2. **AI quality** — confidence distribution, validation failure rate by check, injection
   detections, empty-retrieval rate.
3. **AI cost/latency** — cost per decision, tokens by model, per-agent p50/p95 latency.
4. **Infrastructure** — request latency and errors, consumer lag, DLQ depth, DB latency, cache
   hit ratio.

Every panel must be backed by a real metric from a real run. No decorative panels.

## Verification (Phase 8 acceptance)

An integration test asserts trace continuity: one trace id spanning HTTP → Kafka → AI service →
agent nodes. Metric existence is asserted. The same `correlation_id` must be findable in Java logs,
Python logs and the trace for one request.

## Rules

1. New component → emits latency, errors, and (if it calls an LLM) tokens and cost. Not optional.
2. Cost figures are labelled **estimates** — pricing tables drift.
3. Never instrument by logging in a hot loop; use metrics.
4. If a failure cannot be diagnosed from telemetry alone, the telemetry is incomplete — fix that
   as part of the fix.
