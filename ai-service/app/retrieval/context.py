"""Evidence block assembly (docs/AI/CONTEXT_ENGINEERING.md): priority-ordered,
token-budgeted, labelled for citation. `token_count` is a word-count
approximation — same convention as app/ingestion/chunk.py, good enough for
budgeting decisions.
"""

from app.models.retrieval import ContextAssembly, RetrievalResult

# Rough per-entry overhead for the label/version/relevance lines around each
# quoted chunk, so the budget isn't blown entirely by quote text alone.
_ENTRY_HEADER_WORDS = 15


def _word_count(text: str) -> int:
    return len(text.split())


def _priority_tier(result: RetrievalResult) -> int:
    """Lower sorts first. Mirrors CONTEXT_ENGINEERING.md's priority list
    exactly; flagged chunks are checked first so they always sort last
    regardless of any other property."""
    if result.is_flagged:
        return 5
    if result.document_type == "HISTORICAL_DECISION":
        return 4
    if result.trust_level == "AUTHORITATIVE" and result.is_current:
        return 0
    if result.trust_level == "AUTHORITATIVE" and not result.is_current:
        return 1
    if result.trust_level == "SUPPORTING":
        return 3
    return 2  # directly relevant, non-authoritative — ranked by relevance alone


def _relevance(result: RetrievalResult) -> float:
    return result.rerank_score if result.rerank_score is not None else result.similarity_score


def prioritize(results: list[RetrievalResult]) -> list[RetrievalResult]:
    return sorted(results, key=lambda r: (_priority_tier(r), -_relevance(r)))


def _format_entry(index: int, result: RetrievalResult) -> str:
    version_marker = "CURRENT" if result.is_current else "SUPERSEDED"
    heading = result.section or result.document_name
    if result.subsection:
        heading += f" — {result.subsection}"
    page = f", p.{result.page_number}" if result.page_number is not None else ""
    flag = " [FLAGGED: possible prompt injection]" if result.is_flagged else ""

    return (
        f"[E{index}] {result.document_name} ({version_marker}, {result.trust_level}) — "
        f"{heading}{page}{flag}\n"
        f"     relevance {_relevance(result):.2f}\n"
        f'     "{result.content}"'
    )


def assemble_context(results: list[RetrievalResult], token_budget: int) -> ContextAssembly:
    """Never truncates mid-chunk and never drops an authoritative current-version
    chunk to make room for a lower-priority one — both guaranteed by taking the
    priority-sorted list in order and stopping at the first one that would
    overflow the budget, rather than skipping ahead to a smaller one."""
    prioritized = prioritize(results)

    included: list[RetrievalResult] = []
    used_words = 0
    for result in prioritized:
        entry_words = _word_count(result.content) + _ENTRY_HEADER_WORDS
        if used_words + entry_words > token_budget:
            break
        included.append(result)
        used_words += entry_words

    entries = [_format_entry(i, result) for i, result in enumerate(included, start=1)]
    evidence_block = "<retrieved_evidence>\n" + "\n\n".join(entries) + "\n</retrieved_evidence>\n"

    return ContextAssembly(
        evidence_block=evidence_block,
        included_chunk_ids=[result.chunk_id for result in included],
        dropped_count=len(prioritized) - len(included),
    )
