-- V36: decision requests (plan section 4.1, R24).
--
-- decision_requests: one row per decision a run, a seat or a coordinator raised for an answer. root_id cascades with
-- the root item and item_id with its item (an item delete drops its decisions; a root delete drops every decision of
-- the root). run_id has NO foreign key on purpose, like runs.scope_id and runs.pause_decision_id: a run disappears only
-- with its root, which already cascades the decision through root_id, and an FK added later would need a table
-- recreation.
--
-- class names a decision class of the config; answerers is the snapshot of who may answer it, taken at raise time.
-- severity, status and on_expiry are closed vocabularies. scope_key is the item id text when the decision names an
-- item, else the run id text, else the literal 'root'; dedupe_key is the raiser's key within that scope.
-- question is 1 to 500 characters; options (1 to 8 {key, label, consequence}) and links (at most 10 {kind, ref}) are
-- JSON text validated by the service, never read with JSON functions; the whole payload is capped at 4 KiB by the
-- service. Timestamps are the canonical 23-character UTC text of V20, so text order is time order. expires_at is NULL
-- when the class sets no expiry (never overdue). The answer columns carry the answer and its provenance; principal id,
-- host and session id are stored but shown only to an ADMIN reader. after_expiry is 0/1 (SQLite has no BOOLEAN).
--
-- Uniqueness: at most one non-withdrawn decision per (root_id, scope_key, dedupe_key). The index is partial, so a
-- withdrawn row leaves it and a re-raise after a withdraw creates a new row.
--
-- Additive only: one new table with four indexes; no existing object is touched, no data is migrated.

CREATE TABLE decision_requests (
    id                  BLOB    PRIMARY KEY NOT NULL,
    root_id             BLOB    NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    run_id              BLOB,
    item_id             BLOB    REFERENCES work_items(id) ON DELETE CASCADE,
    class               TEXT    NOT NULL CHECK (length(class) BETWEEN 1 AND 64),
    answerers           TEXT    NOT NULL CHECK (answerers IN ('human-only', 'director-or-human', 'any-actor')),
    severity            TEXT    NOT NULL CHECK (severity IN ('high', 'normal', 'low')),
    status              TEXT    NOT NULL CHECK (status IN ('open', 'acknowledged', 'answered', 'withdrawn')),
    dedupe_key          TEXT    NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 128),
    scope_key           TEXT    NOT NULL CHECK (length(scope_key) BETWEEN 1 AND 36),
    question            TEXT    NOT NULL CHECK (length(question) BETWEEN 1 AND 500),
    options             TEXT    NOT NULL,
    recommended         TEXT    CHECK (recommended IS NULL OR length(recommended) <= 32),
    links               TEXT,
    context             TEXT    CHECK (context IS NULL OR length(context) <= 1000),
    raised_by_id        TEXT    NOT NULL,
    raised_by_kind      TEXT    NOT NULL,
    raised_by_seat      TEXT,
    raised_at           TEXT    NOT NULL CHECK (length(raised_at) = 23),
    expires_at          TEXT    CHECK (expires_at IS NULL OR length(expires_at) = 23),
    on_expiry           TEXT    NOT NULL CHECK (on_expiry IN ('escalate')),
    ack_by_id           TEXT,
    ack_by_kind         TEXT,
    ack_at              TEXT    CHECK (ack_at IS NULL OR length(ack_at) = 23),
    answer_option       TEXT    CHECK (answer_option IS NULL OR length(answer_option) <= 32),
    answer_text         TEXT    CHECK (answer_text IS NULL OR length(answer_text) <= 1000),
    answer_rationale    TEXT    CHECK (answer_rationale IS NULL OR length(answer_rationale) <= 1000),
    answered_by_id      TEXT,
    answered_by_kind    TEXT,
    answered_by_proof   TEXT,
    answered_host       TEXT,
    answered_session_id TEXT,
    answered_run_id     BLOB,
    answered_seat       TEXT,
    answered_profile    TEXT,
    answered_at         TEXT    CHECK (answered_at IS NULL OR length(answered_at) = 23),
    after_expiry        INTEGER NOT NULL DEFAULT 0 CHECK (after_expiry IN (0, 1)),
    version             INTEGER NOT NULL DEFAULT 1,
    CHECK (
        (status = 'answered' AND answered_at IS NOT NULL AND answered_by_id IS NOT NULL)
        OR (status <> 'answered' AND answered_at IS NULL AND answered_by_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_decision_requests_key ON decision_requests(root_id, scope_key, dedupe_key) WHERE status <> 'withdrawn';
CREATE INDEX idx_decision_requests_run_status ON decision_requests(run_id, status);
CREATE INDEX idx_decision_requests_item ON decision_requests(item_id);
CREATE INDEX idx_decision_requests_root_status_severity ON decision_requests(root_id, status, severity);
