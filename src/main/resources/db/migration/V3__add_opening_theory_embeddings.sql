CREATE EXTENSION IF NOT EXISTS vector;

-- Variation/sub-variation tree for one opening. parent_variation_id is a
-- self-reference so lines can nest to arbitrary depth; identifying_moves is the
-- full SAN sequence from move 1, used for deterministic prefix matching against a
-- request's move history before any embedding retrieval runs.
CREATE TABLE opening_variations (
    id                  BIGSERIAL PRIMARY KEY,
    opening_id          BIGINT NOT NULL REFERENCES openings (id),
    parent_variation_id BIGINT REFERENCES opening_variations (id),
    name                VARCHAR(100) NOT NULL,
    identifying_moves   JSONB NOT NULL
);

CREATE INDEX idx_opening_variations_opening_id ON opening_variations (opening_id);
CREATE INDEX idx_opening_variations_parent_variation_id ON opening_variations (parent_variation_id);

-- Retrievable units of theory text. variation_id is null for opening-level
-- content (overview, main-line rationale); section mirrors the YAML field the
-- chunk came from (overview, idea, plans_white, traps, ...).
CREATE TABLE theory_chunks (
    id           BIGSERIAL PRIMARY KEY,
    opening_id   BIGINT NOT NULL REFERENCES openings (id),
    variation_id BIGINT REFERENCES opening_variations (id),
    section      VARCHAR(64) NOT NULL,
    content      TEXT NOT NULL,
    embedding    vector(1024) NOT NULL,
    source       VARCHAR(255)
);

CREATE INDEX idx_theory_chunks_opening_id ON theory_chunks (opening_id);
CREATE INDEX idx_theory_chunks_variation_id ON theory_chunks (variation_id);

-- No ANN index (ivfflat/hnsw): the whole corpus is a few hundred chunks, so a
-- sequential scan with exact distance is faster than an approximate index and
-- avoids ivfflat's need for training data. Add one if the corpus grows.
