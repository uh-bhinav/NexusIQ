"""Pure metric functions (docs/AI/EVALUATION.md). Deliberately free of any
graph/DB/LLM dependency so they're cheap, fast, deterministic unit tests
(.claude/rules/testing.md: "heavy unit testing of deterministic logic") —
harness.py is the only caller that wires them to a real run.
"""

import re

from app.evaluation.models import (
    AggregateMetrics,
    CaseResult,
    DecisionCaseMetrics,
    GenerationCaseMetrics,
    RetrievalCaseMetrics,
)

# --- Retrieval ---


def recall_at_k(retrieved_ranked: list[str], relevant: set[str], k: int) -> float:
    if not relevant:
        return 1.0
    top_k = set(retrieved_ranked[:k])
    return len(top_k & relevant) / len(relevant)


def precision_at_k(retrieved_ranked: list[str], relevant: set[str], k: int) -> float:
    top_k = retrieved_ranked[:k]
    if not top_k:
        return 0.0
    hits = sum(1 for item in top_k if item in relevant)
    return hits / len(top_k)


def reciprocal_rank(retrieved_ranked: list[str], relevant: set[str]) -> float:
    if not relevant:
        return 1.0
    for rank, item in enumerate(retrieved_ranked, start=1):
        if item in relevant:
            return 1.0 / rank
    return 0.0


def compute_retrieval_metrics(
    retrieved_ranked: list[str], relevant: set[str]
) -> RetrievalCaseMetrics:
    return RetrievalCaseMetrics(
        recall_at_5=recall_at_k(retrieved_ranked, relevant, 5),
        recall_at_10=recall_at_k(retrieved_ranked, relevant, 10),
        precision_at_5=precision_at_k(retrieved_ranked, relevant, 5),
        reciprocal_rank=reciprocal_rank(retrieved_ranked, relevant),
        retrieved_count=len(retrieved_ranked),
    )


# --- Generation ---

_STOPWORDS = {
    "a",
    "an",
    "the",
    "is",
    "are",
    "was",
    "were",
    "be",
    "been",
    "and",
    "or",
    "not",
    "to",
    "of",
    "in",
    "on",
    "for",
    "this",
    "that",
    "with",
    "as",
    "at",
    "by",
    "it",
    "its",
}


def _keywords(text: str) -> set[str]:
    words = re.findall(r"[a-z0-9]+", text.lower())
    return {w for w in words if w not in _STOPWORDS and len(w) > 2}


def _claim_present(claim: str, haystack: str, threshold: float = 0.6) -> bool:
    """Heuristic, not semantic: a keyword-overlap proxy for "does this
    forbidden claim appear (possibly paraphrased) in the output", used
    alongside the validator's own HALLUCINATION check, not instead of it.
    Documented limitation: this can both over- and under-match paraphrases —
    docs/AI/EVALUATION.md's "Human review" section exists precisely because
    automated proxies like this one are not the final word."""
    claim_words = _keywords(claim)
    if not claim_words:
        return False
    haystack_words = _keywords(haystack)
    overlap = len(claim_words & haystack_words) / len(claim_words)
    return overlap >= threshold


def compute_generation_metrics(
    *,
    validation_checks: dict[str, bool] | None,
    must_not_claim: list[str],
    output_text: str,
) -> GenerationCaseMetrics:
    groundedness = None
    citation_validity_rate = None
    hallucination_check_failed = False
    if validation_checks is not None:
        if "EVIDENCE_GROUNDING" in validation_checks:
            groundedness = 1.0 if validation_checks["EVIDENCE_GROUNDING"] else 0.0
        if "CITATION_VALIDITY" in validation_checks:
            citation_validity_rate = 1.0 if validation_checks["CITATION_VALIDITY"] else 0.0
        hallucination_check_failed = not validation_checks.get("HALLUCINATION", True)

    matched_claims = [claim for claim in must_not_claim if _claim_present(claim, output_text)]
    hallucinated = hallucination_check_failed or bool(matched_claims)

    return GenerationCaseMetrics(
        groundedness=groundedness,
        citation_validity_rate=citation_validity_rate,
        hallucinated=hallucinated,
        hallucinated_claims=matched_claims,
    )


# --- Decision ---


def compute_decision_metrics(
    *,
    actual_recommendation: str,
    expected_recommendations: list[str],
    actual_policy_statuses: dict[str, str],
    expected_policy_statuses: dict[str, str],
    actual_requires_human_approval: bool,
    expected_requires_human_approval: bool | None,
    actual_decision_type: str | None = None,
    expected_decision_type: str | None = None,
) -> DecisionCaseMetrics:
    recommendation_correct = actual_recommendation in expected_recommendations
    intent_correct = (
        actual_decision_type == expected_decision_type
        if expected_decision_type is not None and actual_decision_type is not None
        else None
    )

    policy_status_accuracy = None
    if expected_policy_statuses:
        matched = 0
        for expected_name, expected_status in expected_policy_statuses.items():
            actual_status = next(
                (
                    status
                    for name, status in actual_policy_statuses.items()
                    if expected_name.lower() in name.lower()
                    or name.lower() in expected_name.lower()
                ),
                None,
            )
            if actual_status == expected_status:
                matched += 1
        policy_status_accuracy = matched / len(expected_policy_statuses)

    escalation_correct = None
    if expected_requires_human_approval is not None:
        escalation_correct = actual_requires_human_approval == expected_requires_human_approval

    return DecisionCaseMetrics(
        recommendation_correct=recommendation_correct,
        actual_recommendation=actual_recommendation,
        policy_status_accuracy=policy_status_accuracy,
        escalation_correct=escalation_correct,
        actual_requires_human_approval=actual_requires_human_approval,
        intent_correct=intent_correct,
        actual_decision_type=actual_decision_type,
    )


# --- Aggregation ---


def _mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def aggregate(results: list[CaseResult]) -> AggregateMetrics:
    ok = [r for r in results if r.error is None]
    errors = [r for r in results if r.error is not None]

    retrieval = [r.retrieval for r in ok if r.retrieval is not None]
    generation = [r.generation for r in ok if r.generation is not None]
    decision = [r.decision for r in ok if r.decision is not None]

    escalation_expected = [
        (d.actual_requires_human_approval, d.escalation_correct)
        for d in decision
        if d.escalation_correct is not None
    ]
    # escalation_correct alone can't distinguish precision from recall — recompute
    # from the pairing of actual vs (actual == expected) by inverting when they
    # disagree: if correct, actual==expected; if incorrect, actual!=expected, so
    # expected = not actual.
    tp = fp = fn = 0
    for actual, correct in escalation_expected:
        expected = actual if correct else (not actual)
        if actual and expected:
            tp += 1
        elif actual and not expected:
            fp += 1
        elif not actual and expected:
            fn += 1

    return AggregateMetrics(
        case_count=len(results),
        error_count=len(errors),
        recall_at_5=_mean([r.recall_at_5 for r in retrieval]) or 0.0,
        recall_at_10=_mean([r.recall_at_10 for r in retrieval]) or 0.0,
        precision_at_5=_mean([r.precision_at_5 for r in retrieval]) or 0.0,
        mrr=_mean([r.reciprocal_rank for r in retrieval]) or 0.0,
        empty_result_rate=(
            sum(1 for r in retrieval if r.retrieved_count == 0) / len(retrieval)
            if retrieval
            else 0.0
        ),
        groundedness=_mean([g.groundedness for g in generation if g.groundedness is not None]),
        citation_validity_rate=_mean(
            [g.citation_validity_rate for g in generation if g.citation_validity_rate is not None]
        ),
        hallucination_rate=_mean([1.0 if g.hallucinated else 0.0 for g in generation]),
        recommendation_accuracy=_mean([1.0 if d.recommendation_correct else 0.0 for d in decision]),
        intent_accuracy=_mean(
            [1.0 if d.intent_correct else 0.0 for d in decision if d.intent_correct is not None]
        ),
        escalation_precision=(tp / (tp + fp)) if (tp + fp) > 0 else None,
        escalation_recall=(tp / (tp + fn)) if (tp + fn) > 0 else None,
        total_input_tokens=sum(r.total_input_tokens for r in results),
        total_output_tokens=sum(r.total_output_tokens for r in results),
        estimated_cost_usd=sum(r.estimated_cost_usd for r in results),
    )
