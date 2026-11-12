CREATE TABLE IF NOT EXISTS lab_word_stats (
    profile text NOT NULL,
    word text NOT NULL,
    count bigint NOT NULL CHECK (count > 0),
    mean_score double precision NOT NULL,
    bingos bigint NOT NULL CHECK (bingos BETWEEN 0 AND count),
    openers bigint NOT NULL CHECK (openers BETWEEN 0 AND count),
    premium_turns bigint NOT NULL CHECK (premium_turns BETWEEN 0 AND count),
    PRIMARY KEY (profile, word)
);

CREATE INDEX IF NOT EXISTS lab_word_stats_frequency_idx
    ON lab_word_stats (profile, count DESC, word);

CREATE TABLE IF NOT EXISTS lab_move_metrics (
    profile text NOT NULL,
    key text NOT NULL,
    value jsonb NOT NULL,
    PRIMARY KEY (profile, key)
);
