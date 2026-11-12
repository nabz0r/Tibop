CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS lab_vectors (
    id text PRIMARY KEY,
    title text NOT NULL,
    body text NOT NULL,
    source text NOT NULL,
    embedding vector(1024) NOT NULL
);
