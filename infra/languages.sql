CREATE TABLE IF NOT EXISTS lab_profile_games (
    profile text NOT NULL,
    id bigint NOT NULL CHECK (id > 0),
    seed bigint NOT NULL CHECK (seed >= 0),
    a smallint NOT NULL CHECK (a BETWEEN 0 AND 3),
    b smallint NOT NULL CHECK (b BETWEEN 0 AND 3),
    score_a integer NOT NULL,
    score_b integer NOT NULL,
    turns integer NOT NULL CHECK (turns > 0),
    winner smallint CHECK (winner IN (0, 1)),
    payload jsonb NOT NULL,
    PRIMARY KEY (profile, id),
    UNIQUE (profile, seed)
);

CREATE INDEX IF NOT EXISTS lab_profile_games_a_id ON lab_profile_games (profile, a, id DESC);
CREATE INDEX IF NOT EXISTS lab_profile_games_b_id ON lab_profile_games (profile, b, id DESC);

CREATE TABLE IF NOT EXISTS lab_profile_meta (
    profile text NOT NULL,
    key text NOT NULL,
    value jsonb NOT NULL,
    PRIMARY KEY (profile, key)
);
