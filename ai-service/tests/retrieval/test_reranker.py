from app.models.retrieval import RetrievalResult
from app.retrieval.reranker import Reranker

_MODEL = "BAAI/bge-reranker-base"


def _result(chunk_content: str, similarity: float) -> RetrievalResult:
    import uuid

    return RetrievalResult(
        chunk_id=uuid.uuid4(),
        document_id=uuid.uuid4(),
        document_name="Doc",
        document_type="SECURITY_POLICY",
        document_version=1,
        is_current=True,
        content=chunk_content,
        similarity_score=similarity,
        trust_level="SUPPORTING",
        is_flagged=False,
        citation_reference="Doc",
    )


def test_rerank_setsScoreAndOrdersByIt():
    reranker = Reranker(_MODEL)
    query = "What are the EU data residency requirements for vendor data?"
    # Deliberately give the more relevant passage a *lower* initial similarity
    # than a loosely related one, so a measurable reordering proves the
    # cross-encoder — not just the original vector-search order — is what's
    # driving the result (roadmap Phase 3 acceptance criterion 8).
    loosely_related = _result(
        "The company was founded in 2015 and has offices in three countries.", similarity=0.55
    )
    highly_relevant = _result(
        "Personal data of EU data subjects must be processed and stored within the EEA "
        "unless an approved transfer mechanism is documented with Legal.",
        similarity=0.50,
    )

    reranked = reranker.rerank(query, [loosely_related, highly_relevant], top_n=2)

    assert reranked[0].content == highly_relevant.content
    assert reranked[0].rerank_score is not None
    assert reranked[0].rerank_score > reranked[1].rerank_score


def test_rerank_respectsTopN():
    reranker = Reranker(_MODEL)
    results = [_result(f"Passage number {i} about vendor security.", 0.5) for i in range(5)]

    reranked = reranker.rerank("vendor security requirements", results, top_n=2)

    assert len(reranked) == 2


def test_rerank_emptyInput_returnsEmpty():
    reranker = Reranker(_MODEL)
    assert reranker.rerank("anything", [], top_n=5) == []
