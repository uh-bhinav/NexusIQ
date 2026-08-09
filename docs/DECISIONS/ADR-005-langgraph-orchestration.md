# ADR-005: LangGraph for agent orchestration

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 5

## Context

The decision workflow is not a linear chain. It has parallel branches (policy and risk analysis),
a conditional retry loop (validator → back to retrieval/decision, capped), a deterministic routing
gate (approval router), and a suspension point where a human intervenes and the workflow resumes
possibly hours later — across service restarts.

## Problem

What orchestrates the agent workflow, and how is durable, resumable, inspectable state managed?

## Options considered

1. **LangGraph.** Explicit graph of nodes and conditional edges over a single typed state object;
   Postgres checkpointer for durability; native `interrupt()`/resume for human-in-the-loop;
   per-node instrumentation hooks. Cost: a framework dependency in a fast-moving ecosystem.
2. **Hand-rolled orchestrator** (a state machine in Python over the existing `DecisionState`).
   No dependency, total control, and honestly not that much code for seven nodes. But durable
   checkpointing, resume-after-restart, and parallel-branch state merging all have to be written
   and tested from scratch.
3. **LangChain chains / agent executor.** Higher-level and more opinionated; control flow is
   implicit in the abstraction rather than explicit in the graph, which is the opposite of what
   this system needs — the routing logic *is* the governance story.
4. **Orchestrate from Java** (Spring state machine driving Python nodes over HTTP). Keeps workflow
   state in the authoritative service, but means a network round trip per node and reimplementing
   LLM-adjacent concerns in the wrong language.

## Decision

LangGraph orchestrates the agent workflow inside `ai-service`, with a PostgreSQL checkpointer in
a dedicated `langgraph` schema.

## Rationale

The three requirements that decide it are durability across restarts, resumable
human-in-the-loop, and explicit inspectable routing. LangGraph provides all three natively;
option 2 requires building all three.

Explicitness is the deciding property over option 3: every transition in this workflow is
something a reviewer should be able to point at. A graph with named conditional edges is
readable as a governance artefact. An agent executor deciding its own control flow is not.

Option 4 is rejected because workflow *authority* and workflow *execution* are different things —
Java retains authority (it owns the deterministic gate, the approval record, and all persistence,
per ADR-004 and ADR-006) while LangGraph merely executes and suspends.

## Trade-offs accepted

- A framework dependency in a rapidly evolving ecosystem; breaking changes are likely and upgrades
  will need real attention.
- Checkpointer tables live outside Flyway's control — the one documented exception to ADR-001's
  single-migration-owner rule.
- Debugging graph execution is less direct than stepping through plain Python.
- Some LangGraph idioms (parallel branch state merging in particular) are easy to get subtly wrong.

## Consequences

- One `DecisionState` TypedDict is the only thing that flows between nodes. No module-level mutable
  state, no globals.
- Nodes return only the keys they change; they never mutate state in place.
- Routing lives in explicit conditional edges with **named predicate functions** — never hidden in
  an agent's return value.
- Every edge added must be shown to terminate. `MAX_AGENT_ITERATIONS` is enforced in state and
  asserted in tests.
- Each node is wrapped to record name, status, latency, tokens, cost, error, emitted as a
  `decision.progress` event.
- Adding an eighth agent requires its own ADR.

## Revisit when

LangGraph's API churn costs more than the checkpointing and interrupt machinery would cost to own.
With seven nodes, option 2 remains a realistic fallback and the state object is deliberately
framework-agnostic to keep that door open.
