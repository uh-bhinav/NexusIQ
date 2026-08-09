# ADR-006: Deterministic gate + LangGraph interrupt for human-in-the-loop

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 6–7

## Context

Some decisions must not be finalised by a model. The system must decide *when* a human is required,
suspend the workflow, present the full reasoning to an authorised person, and resume with their
verdict recorded.

The governing principle (CLAUDE.md #2) is that the LLM recommends and never authorises.

## Problem

Who decides that a human is needed, who owns the approval state, and how does a suspended workflow
resume correctly across restarts?

## Options considered

**Who decides escalation:**
1. **The decision agent decides** (returns `requires_human_approval`). Simple — and fatally wrong:
   the component being governed would be choosing whether to be governed. A manipulated or
   overconfident model could route around its own oversight.
2. **A deterministic threshold gate** over the validated outputs. Auditable, testable, tunable via
   config, cannot be argued with by a model.

**Where the workflow suspends:**
3. **Python terminates; Java owns everything after.** Clean authority, but the run is over — there
   is no reasoning context to resume into for the finalisation step.
4. **LangGraph `interrupt()`; Java triggers resume.** Durable suspension in the checkpointer,
   context preserved, resume driven by an authoritative event.

## Decision

Escalation is decided by a **deterministic gate in Java** (option 2), and the workflow suspends via
**LangGraph `interrupt()`** and resumes on the `approval.completed` event (option 4).

The gate escalates when **any** of the following holds:

```
any policy finding has status VIOLATED
  OR risk_level >= HITL_ESCALATE_ON_RISK           (default HIGH)
  OR confidence < HITL_MIN_CONFIDENCE              (default 0.75)
  OR evidence_coverage < HITL_MIN_EVIDENCE_COVERAGE(default 0.80)
  OR validation escalated after MAX_AGENT_ITERATIONS
  OR a PROMPT_INJECTION_ATTEMPT finding was raised
```

The gate contains **zero LLM calls**. It reads validated, schema-constrained fields and applies
thresholds from configuration.

## Rationale

The split assigns each concern to the layer that can be trusted with it. Java owns *authority* —
who may approve, whether approval is required, what gets recorded. LangGraph owns *execution* —
suspending and resuming a stateful run. Neither is asked to do the other's job.

Putting the gate in Java rather than in the Python graph also means the escalation rule sits in the
same transactional service as the approval record and the audit trail, so "was approval required,
and was it obtained?" is answerable from one consistent place.

This is the architectural point of the whole project: **a deterministic policy layer wrapped around
a probabilistic system.** Placing the gate anywhere the model can influence would forfeit it.

## Trade-offs accepted

- Thresholds are blunt. A genuinely safe decision at confidence 0.74 still escalates. Accepted:
  false escalation is cheap, false autonomy is not.
- Escalation behaviour depends on the model's self-reported confidence being meaningfully
  calibrated. It partly is not — which is why coverage, violations and validation outcomes are
  independent triggers rather than confidence alone.
- Resume-after-interrupt is a real failure surface (restarts, duplicate events) and needs explicit
  tests.
- Cross-service coordination: Java holds the approval, Python holds the suspended run.

## Consequences

- Separation of duties: a requester may never approve their own decision (`403`), enforced in the
  service and tested.
- Only `APPROVER`/`ADMIN` may act on the queue.
- The approver is shown everything: recommendation, confidence, risk, all findings, evidence with
  resolvable citations, the agent trace, the validation result, and the missing information.
- `approval.completed` must be idempotent — a duplicate must not double-resume the run.
- Thresholds are configuration, not code, and every change to them is a tuning decision to record.
- Escalation precision/recall is an evaluation metric (Phase 10), not a guess.

## Revisit when

Evaluation shows escalation rate is impractically high (reviewer fatigue) or dangerously low.
Tune thresholds first; change the rule structure only with a new ADR.
