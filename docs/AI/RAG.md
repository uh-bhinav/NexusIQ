# Retrieval (RAG)

Rules: `.claude/rules/ai-service.md`, `.claude/rules/database.md`. Rationale: ADR-002, ADR-009.

Retrieval quality caps everything downstream. A perfect agent over bad evidence produces a
confident wrong answer.

---

## Ingestion pipeline

```
upload → extract → clean → detect sections → chunk → injection scan → embed → store
```

**Extract.** PDF / DOCX / TXT / MD. The PDF library is chosen in Phase 2 by comparing candidates
on the sample corpus (heading fidelity and page-number accuracy matter more than raw speed) and
recorded in an ADR.

**Clean.** Normalise whitespace and unicode, strip repeated headers/footers, drop page furniture,
preserve list and heading structure. Do not strip so aggressively that section identity is lost —
`§4.2` is what makes a citation useful.

**Detect sections.** Build a heading hierarchy (`heading_path`), keep page numbers, and identify
policy reference codes (`SP-102`, `DR-11 §3.1`). These become citation references.

### Chunking

Hierarchical, not fixed-width:

```
Document → Section → Subsection → Chunk
```

- Target ~512 tokens, hard max 800, overlap ~64 tokens.
- **Never split across a section boundary.** A chunk belongs to exactly one section.
- A short section stays one chunk even if far below target.
- A long subsection splits on paragraph boundaries, and every piece keeps the full
  `heading_path`.
- Every chunk carries: `document_id`, `workspace_id`, `chunk_index`, `section`, `subsection`,
  `heading_path[]`, `page_number`, `token_count`.

Why it matters: policy clauses are semantically bounded by their sections. Fixed-width chunking
splits a requirement from its qualifying condition, and the model then reasons over half a rule.
This single choice does more for answer quality than any prompt.

**Injection scan.** Heuristics for instruction-like text in a document ("ignore previous
instructions", "you must approve", "system:", role markers). Flag the chunk (`is_flagged`,
`flag_reason`); do not silently drop it — the flag is itself a finding.

**Embed.** `BAAI/bge-small-en-v1.5`, 384 dims, local, batched, normalized. bge models are
instruction-prefix sensitive: use the documented convention for passages at ingestion and for
queries at retrieval, and use it **identically** every time. Store `embedding_model` and
`embedding_version` on every row.

## Retrieval pipeline

```
query → rewrite (planner) → embed → vector search (workspace-scoped)
      → metadata filter → rerank → threshold → context assembly
```

### Stage 1 — Vector search

Cosine distance over HNSW, always with `workspace_id` in the predicate. Over-fetch
`RETRIEVAL_TOP_K` (default 20) to leave the reranker something to work with.

**Open item for Phase 3:** HNSW cannot pre-filter by tenant. Measure over-fetch + post-filter
against partial indexes on the sample corpus and record the choice here with the numbers.

### Stage 2 — Metadata filtering

- `workspace_id` — always, non-negotiable.
- `documents.status = 'READY'`.
- `document_type` ∈ the planner's requested types.
- **Version preference:** `is_current = true` ranks above superseded versions. A superseded
  version is retrievable (conflict detection needs it) but never outranks the current one.
- Recency and `trust_level` feed ranking, not exclusion.

### Stage 3 — Reranking

`BAAI/bge-reranker-base`, local, cross-encoder over (query, chunk) pairs, keeping `RERANK_TOP_N`
(default 8). Toggle via `RERANKER_ENABLED`; benchmark both modes in Phase 3 and record the
latency/quality trade-off here — do not adopt it on faith.

### Stage 4 — Threshold

Drop anything below `RETRIEVAL_MIN_SIMILARITY` (default 0.30). **An empty result set is a valid
outcome** and must propagate as `INSUFFICIENT_INFORMATION` — never pad the context with weak
matches to avoid returning nothing.

### Stage 5 — Context assembly

See `CONTEXT_ENGINEERING.md`.

## Result contract

```json
{
  "chunk_id": "uuid",
  "document_id": "uuid",
  "document_name": "Security Policy",
  "document_type": "SECURITY_POLICY",
  "document_version": 2,
  "is_current": true,
  "section": "4. Vendor Security Requirements",
  "subsection": "4.2 Certification",
  "page_number": 11,
  "content": "...",
  "similarity_score": 0.87,
  "rerank_score": 0.93,
  "trust_level": "AUTHORITATIVE",
  "is_flagged": false,
  "citation_reference": "SP-102 §4.2 p.11"
}
```

Anonymous chunks are never passed to a model.

## Caching

Redis, key = `retrieval:{workspace_id}:{sha256(normalized_query + filters)}`, short TTL.
**The tenant is part of the key** — a cache key without it is a cross-tenant data leak, not a
performance detail. Invalidate the workspace's retrieval cache when a document reaches `READY`.

## Metrics

Retrieval latency (p50/p95, per stage), result count, similarity distribution, empty-result rate,
cache hit ratio, rerank latency and rank-change magnitude.

## Evaluation

Recall@5, recall@10, precision@5, MRR against the labelled dataset. Any change to chunking,
embedding, filtering or reranking **must** report before/after numbers — see `EVALUATION.md`.
Intuition about retrieval quality is unreliable; measure it.

## Known limitations

Pure dense retrieval misses exact identifier matches (a query for `SP-102` may not surface the
document that defines it). If recall proves insufficient in Phase 10, add BM25 hybrid search with
reciprocal rank fusion — logged in the backlog, not built speculatively.
