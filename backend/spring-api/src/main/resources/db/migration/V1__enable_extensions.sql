-- Extensions required by the schema.
-- vector   : pgvector, for document_chunks.embedding (added in V5, Phase 2).
--            Enabled here so it is available from the first migration onward.
-- pgcrypto : gen_random_uuid() for all primary keys.
-- citext   : case-insensitive email comparison without lower()-wrapping every query.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
