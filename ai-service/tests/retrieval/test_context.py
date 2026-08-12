import uuid

from app.models.retrieval import RetrievalResult
from app.retrieval.context import assemble_context, prioritize


def _result(
    content: str,
    *,
    trust_level: str = "SUPPORTING",
    is_current: bool = True,
    document_type: str = "SECURITY_POLICY",
    is_flagged: bool = False,
    similarity: float = 0.8,
    document_name: str = "Doc",
    document_version: int = 1,
) -> RetrievalResult:
    return RetrievalResult(
        chunk_id=uuid.uuid4(),
        document_id=uuid.uuid4(),
        document_name=document_name,
        document_type=document_type,
        document_version=document_version,
        is_current=is_current,
        content=content,
        similarity_score=similarity,
        trust_level=trust_level,
        is_flagged=is_flagged,
        citation_reference=document_name,
    )


def test_prioritize_ordersByTheDocumentedPriorityList():
    authoritative_current = _result("current auth", trust_level="AUTHORITATIVE", is_current=True)
    authoritative_superseded = _result(
        "superseded auth", trust_level="AUTHORITATIVE", is_current=False
    )
    supporting = _result("supporting evidence", trust_level="SUPPORTING")
    historical = _result("a past decision", document_type="HISTORICAL_DECISION")
    flagged = _result("suspicious content", trust_level="AUTHORITATIVE", is_flagged=True)

    ordered = prioritize(
        [flagged, historical, supporting, authoritative_superseded, authoritative_current]
    )

    assert ordered == [
        authoritative_current,
        authoritative_superseded,
        supporting,
        historical,
        flagged,
    ]


def test_prioritize_flaggedChunkAlwaysLast_evenIfAuthoritative():
    flagged_authoritative = _result(
        "flagged", trust_level="AUTHORITATIVE", is_current=True, is_flagged=True
    )
    ordinary_informational = _result("ordinary", trust_level="INFORMATIONAL")

    ordered = prioritize([flagged_authoritative, ordinary_informational])

    assert ordered[-1] == flagged_authoritative


def test_assembleContext_includesLabelsRelevanceAndQuotedContent():
    result = _result("All vendors must hold ISO 27001 certification.", similarity=0.87)

    assembly = assemble_context([result], token_budget=1000)

    assert "[E1]" in assembly.evidence_block
    assert "relevance 0.87" in assembly.evidence_block
    assert '"All vendors must hold ISO 27001 certification."' in assembly.evidence_block
    assert assembly.included_chunk_ids == [result.chunk_id]
    assert assembly.dropped_count == 0


def test_assembleContext_dropsFromTheBottomWhenBudgetIsTight():
    high_priority = _result(
        "word " * 50, trust_level="AUTHORITATIVE", is_current=True, similarity=0.9
    )
    low_priority = _result("word " * 50, trust_level="INFORMATIONAL", similarity=0.5)

    # Budget fits exactly one ~50-word entry plus header overhead, not two.
    assembly = assemble_context([low_priority, high_priority], token_budget=80)

    assert assembly.included_chunk_ids == [high_priority.chunk_id]
    assert assembly.dropped_count == 1


def test_assembleContext_neverTruncatesMidChunk():
    long_content = "This is a complete sentence that must never be cut off mid-way. " * 3
    result = _result(long_content, similarity=0.9)

    # Budget too small to fit the whole entry.
    assembly = assemble_context([result], token_budget=5)

    assert result.content not in assembly.evidence_block
    assert assembly.included_chunk_ids == []
    assert assembly.dropped_count == 1


def test_assembleContext_emptyResults_producesEmptyEvidenceBlock():
    assembly = assemble_context([], token_budget=1000)

    assert assembly.included_chunk_ids == []
    assert assembly.dropped_count == 0
    assert "<retrieved_evidence>" in assembly.evidence_block
    assert "</retrieved_evidence>" in assembly.evidence_block


def test_assembleContext_twoVersionsOfSameDocument_currentIsExplicitlyLabelledOverSuperseded():
    # .claude/rules/testing.md's 14 required failure scenarios, #3: "Two
    # policy versions -> newer version preferred, and it says why."
    # test_prioritize_ordersByTheDocumentedPriorityList already proves the
    # *ordering* half; this proves the "says why" half — the LLM receiving
    # this block can only explain *why* it prefers v2 if the block itself
    # distinguishes the two versions, not just orders them. Mirrors the real
    # conflicting pair in docs/sample-enterprise/security/security-policy-
    # v1.md (permissive) vs v2.md (EU/EEA-only, explicitly supersedes v1).
    v1 = _result(
        "Vendor-processed customer data may be stored and processed in any "
        "region that provides adequate technical and organizational safeguards.",
        document_name="Security Policy (SP-102)",
        document_version=1,
        is_current=False,
        trust_level="AUTHORITATIVE",
        similarity=0.75,
    )
    v2 = _result(
        "All customer data processed on behalf of in-scope EU/EEA customers "
        "must be stored and processed exclusively within EU/EEA data centers.",
        document_name="Security Policy (SP-102)",
        document_version=2,
        is_current=True,
        trust_level="AUTHORITATIVE",
        similarity=0.82,
    )

    assembly = assemble_context([v1, v2], token_budget=1000)

    assert "Security Policy (SP-102) (CURRENT, AUTHORITATIVE)" in assembly.evidence_block
    assert "Security Policy (SP-102) (SUPERSEDED, AUTHORITATIVE)" in assembly.evidence_block
    # Both texts are present — a downstream LLM has the actual conflicting
    # clauses in front of it, not just version labels with no content to
    # compare, so it genuinely *can* explain the disagreement.
    assert "any region that provides adequate" in assembly.evidence_block
    assert "exclusively within EU/EEA data centers" in assembly.evidence_block
    # The current version sorts first (same priority tier, higher similarity
    # per _relevance — ties within a tier go to the more relevant chunk).
    assert assembly.evidence_block.index("(CURRENT") < assembly.evidence_block.index(
        "(SUPERSEDED"
    )
