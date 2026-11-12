CREATE TABLE IF NOT EXISTS lab_games (
    id bigint PRIMARY KEY CHECK (id > 0),
    seed bigint NOT NULL UNIQUE CHECK (seed >= 0),
    a smallint NOT NULL CHECK (a BETWEEN 0 AND 3),
    b smallint NOT NULL CHECK (b BETWEEN 0 AND 3),
    score_a integer NOT NULL,
    score_b integer NOT NULL,
    turns integer NOT NULL CHECK (turns > 0),
    winner smallint CHECK (winner IN (0, 1)),
    payload jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS lab_games_a_id ON lab_games (a, id DESC);
CREATE INDEX IF NOT EXISTS lab_games_b_id ON lab_games (b, id DESC);

CREATE TABLE IF NOT EXISTS lab_meta (
    key text PRIMARY KEY,
    value jsonb NOT NULL
);
