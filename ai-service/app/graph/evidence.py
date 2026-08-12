"""Resolves the [E1], [E2]... labels an agent cites (docs/AI/CONTEXT_ENGINEERING.md:
"The model cites by label; labels map back to chunk_id for deterministic
citation validation") back to real chunk_id values, using the same 1-indexed
order the evidence block was assembled in (retrieval/context.py).

A label the model invents or that falls outside the assembled set is dropped
here rather than raised — Phase 5 has no validator yet to escalate a
hallucinated citation, so the safe interim behaviour is "it doesn't count as
evidence" rather than "let a fabricated id reach persistence". Phase 6's
validator (CITATION_VALIDITY, HALLUCINATION checks) is the real fix; this is
a floor, not a replacement for it — noted in STATUS.md as a known gap.
"""

import uuid


def resolve_evidence_labels(labels: list[str], included_chunk_ids: list[uuid.UUID]) -> list[str]:
    resolved: list[str] = []
    for label in labels:
        if not label.startswith("E"):
            continue
        try:
            index = int(label[1:]) - 1
        except ValueError:
            continue
        if 0 <= index < len(included_chunk_ids):
            resolved.append(str(included_chunk_ids[index]))
    return resolved
