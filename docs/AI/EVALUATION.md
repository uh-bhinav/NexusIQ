# AI Evaluation

An AI system without evaluation is a demo. Every claim about quality in this project must have a
number behind it.

Phase 10 delivers the harness and the baseline. The dataset should be started during Phase 5 —
writing cases early exposes design problems while they are still cheap.

---

## Dataset

`ai-service/evaluation/datasets/` — **≥30 labelled cases**, JSON, versioned, committed.

```json
{
  "id": "EVAL-007",
  "question": "Should Vendor Alpha be approved for European production deployment?",
  "category": "unknown_evidence",
  "expected": {
    "decision_type": "vendor_approval",
    "required_domains": ["security", "data_residency", "procurement"],
    "relevant_document_ids": ["security-policy-v2", "dr-11", "vendor-alpha-dpa"],
    "policy_statuses": {
      "EU Data Residency Policy": "UNKNOWN",
      "Security Policy": "SATISFIED",
      "Vendor Approval Policy": "SATISFIED"
    },
    "recommendation": ["CONDITIONAL_APPROVAL", "INSUFFICIENT_INFORMATION"],
    "requires_human_approval": true,
    "must_not_claim": ["EU data residency is satisfied"]
  }
}
```

`recommendation` accepts a set — more than one answer can be defensible, and pretending otherwise
produces a metric that rewards luck. `must_not_claim` is the important field: it catches
confabulation directly.

### Required coverage

| Category | Min cases | What it proves |
|---|---|---|
| Clean approval | 4 | Happy path works |
| Conditional approval | 4 | Partial satisfaction handled |
| Rejection | 4 | Violations are caught |
| Unknown / missing evidence | 5 | **Does not confabulate** |
| Conflicting versions | 3 | Prefers current, explains the conflict |
| No relevant evidence | 3 | Returns `INSUFFICIENT_INFORMATION` |
| Prompt injection | 3 | Ignores the instruction, raises the finding |
| Out-of-scope question | 2 | Returns `unsupported` |
| Ambiguous question | 2 | Populates `missing_information` |

Include cases the system currently gets **wrong** and record them. An eval set that only confirms
what already works measures nothing.

## Metrics

**Retrieval** — recall@5, recall@10, precision@5, MRR, empty-result rate. Measured against
`relevant_document_ids`.

**Generation** — groundedness (claims with ≥1 valid citation ÷ substantive claims), citation
validity rate (deterministic set membership), hallucination rate (violations of
`must_not_claim`), `UNKNOWN` precision/recall.

**Decision** — recommendation accuracy (∈ expected set), policy-status accuracy per policy,
escalation precision/recall (did the gate escalate exactly what should have been escalated).

**Operational** — p50/p95 latency per node and per run, tokens per run, cost per run, failure rate.

The two that matter most for this system's actual claim: **hallucination rate** and
**`UNKNOWN` recall**. Anyone can get the happy path right.

## Harness

```
make eval                    # full run, mock provider (deterministic, free)
make eval PROVIDER=gemini    # real provider, local only
make eval CASE=EVAL-007      # single case
make eval COMPARE=baseline   # diff against the committed baseline
```

Outputs a per-case result table, aggregate metrics, and a diff against
`docs/AI/EVALUATION_BASELINE.md`.

CI runs the harness with the `mock` provider so results are deterministic and cost nothing. Real
provider runs happen locally and their numbers are what get committed as the baseline.

## Rules

1. **Any change to prompts, model, chunking, retrieval or context assembly requires a
   before/after run.** No exceptions.
2. Report the numbers in the PR/summary, including regressions.
3. A regression beyond the stated threshold blocks the change unless justified explicitly.
4. Baseline lives in `docs/AI/EVALUATION_BASELINE.md`, updated deliberately, never silently.
5. Never tune the dataset to make the system look good. If a case is genuinely mislabelled, fix it
   and say that you did.

## Baseline record

`docs/AI/EVALUATION_BASELINE.md` (created in Phase 10) records, per run: date, commit,
`workflow_version`, `prompt_version`, model, embedding model, every metric, and notes on what
changed. This is what makes "we improved decision accuracy by X" a fact rather than a feeling.

## Model comparison (Phase 10)

At minimum, compare two configurations — e.g. the cheap model on every node vs the heavier model
on synthesis and validation — across accuracy, latency and cost.

The output is a stated trade-off with numbers: *"Model B improved policy-status accuracy from
0.78 to 0.86 and hallucination rate from 0.09 to 0.04, at 2.3× cost and +4.1 s p95 per run;
adopted for the synthesis and validation nodes only."*

That sentence is the entire point of building the evaluation harness.

## Human review

Automated metrics miss reasoning quality. Periodically read a sample of runs end to end and ask:
is the reasoning sound, or merely well-formed? Is the confidence calibrated? Would this
recommendation survive a hostile reviewer? Record what you find — it is the input to the next
prompt revision.
