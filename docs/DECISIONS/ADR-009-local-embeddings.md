# ADR-009: Local `BAAI/bge-small-en-v1.5` embeddings

**Status:** Accepted
**Date:** 2026-08-09
**Phase:** 2

## Context

Every chunk of every ingested document must be embedded, and every query must be embedded at
retrieval time. Ingesting the sample corpus and re-ingesting it repeatedly during development is
routine. Evaluation requires that identical input produces identical vectors, run after run.

## Problem

What produces embeddings, at what cost, and with what reproducibility guarantees?

## Options considered

1. **Local `sentence-transformers` model inside the AI service.** Zero marginal cost, no rate
   limits, no network dependency in the ingestion path, byte-identical output across runs.
   Costs ~1–2 GB of image size and CPU time.
2. **Provider embedding API** (Gemini/OpenAI). Small image, fast, no model management. Costs money
   per re-ingest, adds a network failure mode to ingestion, rate-limited, and the provider can
   change the model under a stable name — silently invalidating an index.
3. **A separate embedding microservice.** Independent scaling; an entire extra service, network
   hop, and deployment unit for a function call.

## Decision

Embeddings are generated **locally, in-process, inside the AI service**, using
`BAAI/bge-small-en-v1.5` (384 dimensions), behind an `EmbeddingProvider` abstraction. No separate
container, no separate service, no paid API in the default path.

## Rationale

Cost and reproducibility decide it. Ingestion will be run dozens of times during development; a
per-token API bill for that conflicts directly with ADR-010. More importantly, evaluation results
must be comparable across weeks — a provider silently updating a model behind a stable name would
invalidate the retrieval baseline without any visible signal.

`bge-small-en-v1.5` is chosen for its quality-per-size on English retrieval: 384 dimensions keeps
vectors and the HNSW index small (a third of the storage and index cost of a 1024-dim model) while
performing well on retrieval benchmarks. English-only is acceptable — the corpus is English.

Option 3 adds a deployment unit to wrap what is a function call; it would violate the "no
unnecessary services" principle outright.

## Trade-offs accepted

- Docker image grows by ~1–2 GB (model weights + torch). First run downloads the model — must be
  pre-baked into the image or cached in a volume, and documented (Phase 12).
- CPU-bound embedding: slower than an API for large batches, and it competes with the rest of the
  service for CPU. Mitigated by batching (`EMBEDDING_BATCH_SIZE`).
- English-only, and a smaller model than the best available — some retrieval quality is given up
  relative to a large API model. Measured in Phase 10, not assumed.
- Cold start includes model load; `/ready` must not report ready until it is loaded.

## Consequences

- `document_chunks.embedding` is `vector(384)`. Changing the model changes the dimension and
  requires a Flyway migration plus a full re-embed.
- **Every chunk stores `embedding_model` and `embedding_version`.** Changing `EMBEDDING_MODEL` or
  `EMBEDDING_MODEL_VERSION` triggers a **controlled re-embedding process** — never a silent mix of
  vectors from different models in one index. Mixed vectors produce ranking that is wrong in ways
  no test will catch.
- Query embeddings must use the same model **and the same instruction-prefix convention** as
  ingestion (bge models are prefix-sensitive; document this once and follow it everywhere).
- Vectors are normalized; cosine distance (`<=>`) is used consistently.
- The reranker (`BAAI/bge-reranker-base`) follows the same local, in-process pattern.
- Swapping to an API provider later is an `EmbeddingProvider` implementation plus a re-embed.

## Revisit when

Corpus size makes local embedding throughput the bottleneck, multilingual documents are required,
or Phase 10 evaluation shows retrieval quality is the limiting factor — in which case try a larger
local model (`bge-base`) before reaching for a paid API.
