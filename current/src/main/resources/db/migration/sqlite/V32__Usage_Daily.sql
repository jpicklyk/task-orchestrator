-- V32: Usage daily -- the per-day summary of call_log (the fold) and its watermark (plan R13).
--
-- usage_daily holds one row per (UTC day, dimensions) group, written by the retention pruner's fold from the
-- completed days of call_log, so usage reports reach past the raw retention window. usage_fold is the one-row
-- watermark: the last UTC day that has been folded (call_log rows are pruned only for folded days).
--
-- Pure CREATE TABLE: no existing table or row is touched, no foreign key (a summary row outlives the root it names)
-- and no extra index (the primary key autoindex leads with day, which serves every window read).
--
-- Every dimension is NOT NULL: SQLite treats NULLs as distinct in a primary key, so a group with an absent dimension
-- could never be upserted idempotently. An absent dimension is stored as a sentinel: the empty string for the TEXT
-- dimensions and the zero-length blob x'' for root_id (a real id is 16 bytes). Reads map the sentinels back to NULL.
--
-- day is the UTC date yyyy-MM-dd (10 characters); folded_at is the canonical 23-character UTC timestamp.

CREATE TABLE usage_daily (
    day                 TEXT    NOT NULL CHECK (length(day) = 10),
    root_id             BLOB    NOT NULL,
    tool                TEXT    NOT NULL,
    operation           TEXT    NOT NULL,
    surface             TEXT    NOT NULL,
    host                TEXT    NOT NULL,
    principal_kind      TEXT    NOT NULL,
    outcome             TEXT    NOT NULL CHECK (outcome IN ('ok', 'error')),
    error_code          TEXT    NOT NULL,
    calls               INTEGER NOT NULL CHECK (calls >= 1),
    errors              INTEGER NOT NULL CHECK (errors >= 0),
    request_bytes       INTEGER NOT NULL CHECK (request_bytes >= 0),
    response_bytes      INTEGER NOT NULL CHECK (response_bytes >= 0),
    response_tokens_est INTEGER NOT NULL CHECK (response_tokens_est >= 0),
    latency_ms_sum      INTEGER NOT NULL CHECK (latency_ms_sum >= 0),
    latency_ms_max      INTEGER NOT NULL CHECK (latency_ms_max >= 0),
    retries             INTEGER NOT NULL CHECK (retries >= 0),
    replays             INTEGER NOT NULL CHECK (replays >= 0),
    PRIMARY KEY (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code)
);

CREATE TABLE usage_fold (
    singleton      INTEGER PRIMARY KEY CHECK (singleton = 1),
    folded_through TEXT NOT NULL CHECK (length(folded_through) = 10),
    folded_at      TEXT NOT NULL CHECK (length(folded_at) = 23)
);
