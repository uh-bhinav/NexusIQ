# ADR-002: pgvector instead of a dedicated vector database

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 2–3

## Context

Retrieval must return chunks that are simultaneously: semantically similar to the query, inside the
requesting user's workspace, of the right document type, and from the *current* version of a
policy rather than a superseded one. Every result must carry its document identity for citation.

The corpus is on the order of 10³–10⁴ chunks.

## Problem

Where do embeddings live, and how is vector similarity combined with relational filtering and
authorization?

## Options considered

1. **pgvector inside the existing PostgreSQL.** Vectors sit in the same table as the metadata and
   the tenant key. Similarity, filtering and authorization happen in one SQL statement, inside one
   transaction, with foreign keys to the source document.
2. **Dedicated vector DB (Qdrant / Weaviate / Milvus).** Better ANN tuning and scaling headroom.
   But: another container, another client, duplicated metadata, no referential integrity to
   `documents`, no transactional consistency between "chunks written" and "document READY", and
   tenant filtering re-implemented in a second place — which is a security surface.
3. **Managed vector service (Pinecone etc.).** Recurring cost. Rejected outright by ADR-010.

## Decision

Store embeddings in PostgreSQL using the `pgvector` extension, on `document_chunks`, alongside the
chunk's metadata and `workspace_id`.

## Rationale

The hard requirement here is *filtered* retrieval, not raw ANN throughput. Keeping vectors next to
the tenant key and document metadata makes the workspace predicate part of the same query, which
means tenant isolation is enforced by the same mechanism as everything else in the system rather
than by a second, parallel implementation that can drift.

At 10³–10⁴ chunks, an HNSW index in pgvector is comfortably fast enough; the scaling advantages of
a dedicated engine would be paid for now and realised never.

Adding a second datastore would also violate the project's own principle about not introducing
infrastructure without a concrete engineering reason — and "vector databases are what people use
for RAG" is not one.

## Trade-offs accepted

- Less specialised ANN tuning (no product quantization, no distributed sharding, fewer index types).
- **HNSW cannot pre-filter by tenant.** Filtered queries need either over-fetch + post-filter or
  partial indexes. This must be measured in Phase 3 and the chosen strategy recorded in
  `docs/AI/RAG.md`. This is the real cost of the decision, and it is deliberate.
- Vector operations compete with OLTP traffic for the same connection pool and buffers.
- Reindexing on an embedding-model change is a Postgres migration, not a cheap collection swap.

## Consequences

- `document_chunks.embedding` is `vector(384)`, cosine distance (`<=>`), HNSW index
  (`m=16, ef_construction=64`), normalized vectors, one consistent operator everywhere.
- Every chunk stores `embedding_model` + `embedding_version`; changing either forces a controlled
  re-embed (ADR-009).
- Never `SELECT embedding` unless the vector is actually needed.
- The retrieval layer stays behind an interface, so swapping the backing store later is a
  contained change rather than a rewrite.

## Revisit when

Corpus exceeds ~1M chunks, or measured p95 filtered-retrieval latency exceeds 1 s after tuning
`ef_search` and the filtering strategy.
