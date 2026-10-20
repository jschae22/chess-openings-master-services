-- Ingestion stores a SHA-256 hex digest of each chunk's composed text. On a
-- re-run only chunks whose hash changed are re-embedded, so theory edits can be
-- demoed without rebuilding the whole corpus.
ALTER TABLE theory_chunks ADD COLUMN content_hash VARCHAR(64) NOT NULL;

-- A chunk's identity is (opening, variation-or-none, corpus field it came from).
-- Ingestion upserts on this; COALESCE folds the nullable opening-level rows into
-- a single key space so the unique constraint applies to them too.
CREATE UNIQUE INDEX uq_theory_chunks_identity
    ON theory_chunks (opening_id, COALESCE(variation_id, 0), section);
