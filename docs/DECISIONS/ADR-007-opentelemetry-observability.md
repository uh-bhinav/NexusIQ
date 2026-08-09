# ADR-007: OpenTelemetry as the observability standard

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 8

## Context

A single decision crosses a browser, a Java service, a Kafka topic, a Python service, seven agent
nodes, several LLM calls and dozens of database queries. When it is slow, wrong, or expensive, the
question "where did that happen?" must be answerable in seconds.

AI systems add a dimension conventional observability ignores: **cost**. Tokens spent per node per
run is an operational metric, not a curiosity.

## Problem

How is a distributed, partly-probabilistic workflow made traceable, measurable and debuggable —
across two languages, at $0 cost?

## Options considered

1. **OpenTelemetry** (SDKs + Collector) with local free backends (Jaeger/Tempo, Prometheus,
   Grafana). Vendor-neutral, first-class in both Java and Python, one instrumentation model for
   traces and metrics. Setup cost is real, especially context propagation across Kafka.
2. **Structured logs only**, correlated by `correlation_id`, aggregated with grep/Loki. Much less
   work; no spans, no timing tree, no automatic latency attribution across services.
3. **Spring Actuator + Micrometer on the Java side, ad-hoc Python logging.** Half a solution — the
   AI half, which is where the interesting latency and all of the cost lives, stays opaque.
4. **A managed APM (Datadog, New Relic).** Best UX, rejected by ADR-010 on cost.

## Decision

OpenTelemetry across all services, exported to a local Collector, fanned out to Jaeger/Tempo for
traces and Prometheus + Grafana for metrics. All components run locally in Compose at zero cost.

## Rationale

Option 2 answers "what happened" but never "where did the 40 seconds go". For a system whose whole
claim is production-oriented AI operations, per-agent latency and per-agent cost attribution are
the point, not a nice-to-have — and they require spans.

OpenTelemetry is the only option that instruments Java and Python identically, keeps trace context
across the Kafka boundary, and stays vendor-neutral: the same instrumentation would export to a
managed backend later without touching application code.

## Trade-offs accepted

- Meaningful setup effort, concentrated in trace-context propagation across Kafka — the standard
  place this breaks.
- Extra containers (collector + Jaeger/Tempo + Prometheus + Grafana) in an already large local
  stack; memory footprint must be measured in Phase 12.
- Instrumentation code in hot paths, and a discipline cost: every new component must emit telemetry
  or it is not done.
- Self-hosted backends mean no retention story beyond the local volume. Acceptable for a local demo.

## Consequences

- `correlation_id` is minted at the API edge and propagated: HTTP header `X-Correlation-Id` → MDC →
  **Kafka envelope (explicitly — automatic propagation does not cross the broker)** → Python
  context → node spans → response `request_id`.
- One trace must span HTTP → publish → consume → graph run → each node → each retrieval → each LLM
  call → completion → SSE emission. A Phase-8 integration test asserts trace continuity.
- LLM spans carry `model`, `input_tokens`, `output_tokens`, `estimated_cost_usd`, `latency_ms`.
- Four metric groups: infrastructure, RAG, AI, business (see `docs/ARCHITECTURE.md` §9).
- **Never** put secrets, document text or prompt bodies in span attributes or logs.
- Pricing for cost estimation lives in a dated, versioned table (`llm/pricing.py`); estimates are
  labelled as estimates.

## Revisit when

The local backends outgrow a laptop, or a hosted deployment ever happens — at which point only the
exporter endpoint changes.
