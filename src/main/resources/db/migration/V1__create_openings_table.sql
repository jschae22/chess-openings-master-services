CREATE TABLE openings (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    side        VARCHAR(5) NOT NULL CHECK (side IN ('WHITE', 'BLACK')),
    moves       JSONB NOT NULL
);
