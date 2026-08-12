"""Unit tests for the evaluation harness's pure metric functions
(.claude/rules/testing.md: heavy unit testing of deterministic logic).
No graph, no DB, no LLM — these are plain functions over plain data."""

from app.evaluation.metrics import (
    aggregate,
    compute_decision_metrics,
    compute_generation_metrics,
    compute_retrieval_metrics,
    precision_at_k,
    recall_at_k,
    reciprocal_rank,
)
from app.evaluation.models import (
    CaseResult,
    DecisionCaseMetrics,
    GenerationCaseMetrics,
    RetrievalCaseMetrics,
)


def test_recallAtK_allRelevantInTopK_returnsOne():
    assert recall_at_k(["a", "b", "c"], {"a", "b"}, 5) == 1.0


def test_recallAtK_relevantOutsideTopK_returnsPartial():
    assert recall_at_k(["x", "y", "a"], {"a", "b"}, 2) == 0.0
    assert recall_at_k(["x", "y", "a"], {"a", "b"}, 3) == 0.5


def test_recallAtK_noExpectedRelevantDocs_isVacuouslyOne():
    # The "no relevant evidence" category expects relevant_document_ids=[];
    # there is nothing to have missed, so recall is trivially perfect —
    # precision@k is the metric that actually penalizes over-retrieval here.
    assert recall_at_k(["x", "y"], set(), 5) == 1.0
    assert recall_at_k([], set(), 5) == 1.0


def test_precisionAtK_computesHitFraction():
    assert precision_at_k(["a", "x", "b", "y", "z"], {"a", "b"}, 5) == 2 / 5


def test_precisionAtK_emptyRetrieval_isZero():
    assert precision_at_k([], {"a"}, 5) == 0.0


def test_reciprocalRank_firstHitAtRankThree_isOneThird():
    assert reciprocal_rank(["x", "y", "a"], {"a"}) == 1 / 3


def test_reciprocalRank_noHit_isZero():
    assert reciprocal_rank(["x", "y"], {"a"}) == 0.0


def test_computeRetrievalMetrics_bundlesAllFour():
    m = compute_retrieval_metrics(["a", "b"], {"a"})
    assert isinstance(m, RetrievalCaseMetrics)
    assert m.recall_at_5 == 1.0
    assert m.precision_at_5 == 0.5
    assert m.retrieved_count == 2


def test_computeGenerationMetrics_allChecksPassed_notHallucinated():
    m = compute_generation_metrics(
        validation_checks={
            "EVIDENCE_GROUNDING": True,
            "CITATION_VALIDITY": True,
            "HALLUCINATION": True,
        },
        must_not_claim=["the vendor was approved without review"],
        output_text="The vendor's data residency is unresolved and requires human review.",
    )
    assert m.groundedness == 1.0
    assert m.citation_validity_rate == 1.0
    assert m.hallucinated is False
    assert m.hallucinated_claims == []


def test_computeGenerationMetrics_hallucinationCheckFailed_isHallucinated():
    m = compute_generation_metrics(
        validation_checks={"EVIDENCE_GROUNDING": True, "HALLUCINATION": False},
        must_not_claim=[],
        output_text="anything",
    )
    assert m.hallucinated is True


def test_computeGenerationMetrics_mustNotClaimPhraseAppearsInOutput_isHallucinated():
    # Validator's own HALLUCINATION check passes (it can miss a specific
    # forbidden claim, e.g. a stale-policy citation it doesn't recognize as
    # wrong) — the must_not_claim keyword-overlap check is the second,
    # case-specific line of defense this test exercises directly.
    m = compute_generation_metrics(
        validation_checks={"HALLUCINATION": True},
        must_not_claim=["vendors have 72 hours to notify NexusIQ of a data security incident"],
        output_text="Under our current policy, vendors have 72 hours to notify NexusIQ of any "
        "incident.",
    )
    assert m.hallucinated is True
    assert m.hallucinated_claims


def test_computeGenerationMetrics_unrelatedOutput_doesNotFalselyFlag():
    m = compute_generation_metrics(
        validation_checks={"HALLUCINATION": True},
        must_not_claim=["vendors have 72 hours to notify NexusIQ of a data security incident"],
        output_text="The vendor's encryption at rest uses AES-256 as required.",
    )
    assert m.hallucinated is False


def test_computeGenerationMetrics_noValidationResult_leavesGroundednessNone():
    # e.g. the "unsupported" terminal node — the graph never reaches the
    # validator, so there is genuinely nothing to score here, not a zero.
    m = compute_generation_metrics(validation_checks=None, must_not_claim=[], output_text="")
    assert m.groundedness is None
    assert m.citation_validity_rate is None


def test_computeDecisionMetrics_recommendationInExpectedSet_isCorrect():
    m = compute_decision_metrics(
        actual_recommendation="CONDITIONAL_APPROVAL",
        expected_recommendations=["CONDITIONAL_APPROVAL", "APPROVE"],
        actual_policy_statuses={},
        expected_policy_statuses={},
        actual_requires_human_approval=True,
        expected_requires_human_approval=True,
    )
    assert m.recommendation_correct is True
    assert m.escalation_correct is True


def test_computeDecisionMetrics_recommendationNotInExpectedSet_isIncorrect():
    m = compute_decision_metrics(
        actual_recommendation="APPROVE",
        expected_recommendations=["REJECT"],
        actual_policy_statuses={},
        expected_policy_statuses={},
        actual_requires_human_approval=False,
        expected_requires_human_approval=None,
    )
    assert m.recommendation_correct is False
    assert m.escalation_correct is None  # no expectation stated -> not scored


def test_computeDecisionMetrics_policyStatusFuzzyNameMatch():
    m = compute_decision_metrics(
        actual_recommendation="APPROVE",
        expected_recommendations=["APPROVE"],
        actual_policy_statuses={"Security Policy (SP-102)": "SATISFIED"},
        expected_policy_statuses={
            "Security Policy (SP-102)": "SATISFIED",
            "GDPR Compliance Policy": "SATISFIED",
        },
        actual_requires_human_approval=False,
        expected_requires_human_approval=False,
    )
    # One of the two expected policies has a matching (substring) actual
    # finding and agrees; the other was never found at all -> half credit,
    # not a crash and not silently ignored.
    assert m.policy_status_accuracy == 0.5


def test_computeDecisionMetrics_intentComparedWhenBothPresent():
    m = compute_decision_metrics(
        actual_recommendation="APPROVE",
        expected_recommendations=["APPROVE"],
        actual_policy_statuses={},
        expected_policy_statuses={},
        actual_requires_human_approval=False,
        expected_requires_human_approval=False,
        actual_decision_type="unsupported",
        expected_decision_type="unsupported",
    )
    assert m.intent_correct is True


def test_aggregate_computesEscalationPrecisionAndRecallFromCorrectness():
    # 2 cases correctly required approval (true positives), 1 incorrectly
    # required approval when it shouldn't have (false positive), 1
    # incorrectly did NOT require approval when it should have (false
    # negative) -> precision = 2/3, recall = 2/3.
    def _result(actual_requires_approval: bool, correct: bool) -> CaseResult:
        return CaseResult(
            case_id="x",
            category="c",
            question="q",
            retrieval=RetrievalCaseMetrics(
                recall_at_5=1,
                recall_at_10=1,
                precision_at_5=1,
                reciprocal_rank=1,
                retrieved_count=1,
            ),
            generation=GenerationCaseMetrics(hallucinated=False),
            decision=DecisionCaseMetrics(
                recommendation_correct=True,
                actual_recommendation="APPROVE",
                actual_requires_human_approval=actual_requires_approval,
                escalation_correct=correct,
            ),
        )

    results = [
        _result(True, True),
        _result(True, True),
        _result(True, False),  # false positive: escalated but shouldn't have
        _result(False, False),  # false negative: didn't escalate but should have
    ]
    m = aggregate(results)
    assert round(m.escalation_precision, 4) == round(2 / 3, 4)
    assert round(m.escalation_recall, 4) == round(2 / 3, 4)


def test_aggregate_errorCasesExcludedFromMetricsButCounted():
    ok = CaseResult(
        case_id="ok",
        category="c",
        question="q",
        retrieval=RetrievalCaseMetrics(
            recall_at_5=1, recall_at_10=1, precision_at_5=1, reciprocal_rank=1, retrieved_count=1
        ),
        generation=GenerationCaseMetrics(hallucinated=False),
        decision=DecisionCaseMetrics(
            recommendation_correct=True,
            actual_recommendation="APPROVE",
            actual_requires_human_approval=False,
        ),
    )
    errored = CaseResult(case_id="bad", category="c", question="q", error="boom")

    m = aggregate([ok, errored])
    assert m.case_count == 2
    assert m.error_count == 1
    assert m.recall_at_5 == 1.0  # only the ok case contributes
