-- V21: Call log -- one row per MCP tool call and per REST request (plan section 3.8).
--
-- The row key is the call's reqId: 8 characters of lowercase Crockford base32 (40 random bits),
-- the same value the response carries (_meta.reqId / X-Req-Id), the MDC `reqId` key and the
-- events.req_id of every event the call wrote. Rows are appended in batches by a background
-- writer, off the request path; a duplicate req_id is ignored per row (40 bits can collide).
--
-- Pure CREATE TABLE/INDEX: no existing table is touched, no data migration, no foreign key (a row
-- must outlive the items it names) and no CHECK on surface/tool/principal_kind/token_method (they
-- may grow, and widening a CHECK needs a table recreation). NOT NULL on the TEXT primary key is
-- required: SQLite otherwise allows NULL in a non-INTEGER primary key.
--
-- at is the canonical persisted timestamp form: yyyy-MM-dd HH:mm:ss.SSS in UTC, a fixed 23
-- characters, so text order equals time order.

CREATE TABLE call_log (
    req_id              TEXT    NOT NULL PRIMARY KEY CHECK (length(req_id) = 8),
    at                  TEXT    NOT NULL CHECK (length(at) = 23),
    principal_id        TEXT,
    principal_kind      TEXT,
    proof_status        TEXT,
    host                TEXT,
    session_id          TEXT,
    run_id              BLOB,
    seat                TEXT,
    surface             TEXT    NOT NULL,
    tool                TEXT    NOT NULL,
    operation           TEXT,
    target_ids          TEXT,
    target_versions     TEXT,
    request_shape       TEXT,
    outcome             TEXT    NOT NULL CHECK (outcome IN ('ok', 'error')),
    error_code          TEXT,
    attempts            INTEGER NOT NULL CHECK (attempts >= 1),
    latency_ms          INTEGER NOT NULL CHECK (latency_ms >= 0),
    request_bytes       INTEGER,
    response_bytes      INTEGER,
    response_tokens_est INTEGER,
    token_method        TEXT,
    replayed            INTEGER NOT NULL DEFAULT 0 CHECK (replayed IN (0, 1)),
    batch_size          INTEGER,
    failed_count        INTEGER,
    result_count        INTEGER,
    eligible_count      INTEGER
);

CREATE INDEX idx_call_log_at ON call_log(at);
CREATE INDEX idx_call_log_tool_at ON call_log(tool, at);
