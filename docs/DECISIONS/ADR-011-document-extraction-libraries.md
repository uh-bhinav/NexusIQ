# ADR-011: Document extraction libraries (PDF/DOCX/TXT/MD)

**Status:** Accepted
**Date:** 2026-08-10
**Phase:** 2

## Context

Phase 2 ingests PDF, DOCX, TXT and MD documents and must turn each into clean text with page and
section metadata before chunking. Extraction quality directly caps retrieval quality (ADR context:
`.claude/rules/ai-service.md`, roadmap Phase 2 risks) — a table whose rows get flattened into
disconnected lines can separate a policy tier from its threshold in the resulting chunks.

## Problem

Which library extracts PDF text (the hard case; DOCX/TXT/MD have one obvious choice each) with
enough layout fidelity for policy-style documents (numbered clauses, simple tables), without
pulling in infrastructure this project doesn't need.

## Options considered

1. **`pypdf`** — pure-Python, minimal dependency footprint, fast. Its `extract_text()` walks the
   content stream with limited layout reconstruction: table-like content (text positioned in
   columns with no explicit line breaks) comes out as one token per line, losing row association.
2. **`pdfplumber`** — built on `pdfminer.six`; reconstructs text using character positions, so
   words on the same visual line stay on the same line even without a real "table" object. Adds
   `extract_tables()` for ruled tables. Slower (~5x in the benchmark below) and a heavier dependency
   chain, but both are irrelevant at this corpus's scale (single-digit seconds either way for a
   20-page document).
3. **`unstructured`** — general-purpose extraction across many formats with ML-based layout
   detection. Pulls in a large transitive dependency tree (`unstructured-inference`, `nltk`,
   detectron-style models for some modes) for capability this project doesn't need — we already
   split PDF/DOCX/TXT/MD by extension and only need one extractor per format, not a universal one.
   Rejected under the "simplest architecture that satisfies the requirement" rule
   (`CLAUDE.md` non-negotiable #12).

## Decision

- **PDF: `pdfplumber`.**
- **DOCX: `python-docx`** — the standard library for this format; no meaningful alternative to
  benchmark, single well-maintained option, paragraph/table access maps directly onto the
  hierarchical chunker's needs.
- **TXT/MD: stdlib read + a lightweight markdown-heading walk** — no library needed.

## Rationale

Benchmarked both PDF candidates against a synthetic two-page policy PDF
(`ai-service/tests/fixtures/sample_policy.pdf`: numbered sections, a borderless table, a header and
a footer with a page number) built specifically to exercise the failure mode that matters here.

`pypdf` output for the table:
```
Tier
Data Sensitivity
Review Cadence
Tier 1
Regulated PII / PHI
Quarterly
...
```
Every cell on its own line — a chunker working on this text has no signal that "Tier 1" and
"Quarterly" belong together.

`pdfplumber` output for the same table:
```
Tier Data Sensitivity Review Cadence
Tier 1 Regulated PII / PHI Quarterly
...
```
Row association preserved because `pdfplumber` groups text by vertical position before emitting
lines. Everything else (headings, paragraph text, footer) extracted identically from both
libraries. Timing: `pypdf` 0.010s vs `pdfplumber` 0.055s for the 2-page fixture — a difference with
no practical effect at our corpus scale (`docs/sample-enterprise/` is a handful of documents, not a
bulk ingestion pipeline).

## Trade-offs accepted

- `pdfplumber` pulls in `pdfminer.six` and is measurably slower per page than `pypdf`. Accepted:
  layout fidelity matters more than raw throughput for a corpus of this size, and both remain
  sub-second per document.
- `pdfplumber`'s `extract_tables()` only detects tables with actual ruling lines; borderless
  "tables" (common in real vendor PDFs, as in the benchmark fixture) still come through as prose
  lines, not structured rows. The chunker treats them as prose — acceptable, since the row-grouped
  text still keeps the tier/value association a downstream LLM or reader needs; we are not building
  a table-structure-aware chunker in Phase 2.
- No OCR. Scanned/image-only PDFs will extract empty or near-empty text. Out of scope for Phase 2;
  the sample corpus is text-native. Revisit if a real ingested document turns out to be scanned.

## Consequences

- `ai-service/app/ingestion/extract.py` implements one function per format, dispatched by
  detected/declared content type — never by trusting the client's file extension alone (magic-byte
  validation happens in Java before the file ever reaches this service, per
  `.claude/rules/security.md`).
- `tests/fixtures/sample_policy.pdf` is committed as a real extractor test fixture, not just a
  benchmarking scratch file.

## Revisit when

The sample corpus needs a scanned/image PDF (would require OCR, a different tool entirely) or a
document with genuinely complex multi-column layout that defeats `pdfplumber`'s line grouping.
