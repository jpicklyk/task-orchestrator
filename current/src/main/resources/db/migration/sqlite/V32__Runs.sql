-- V32: runs and run membership (plan section 4.1, R17).
--
-- runs: one row per run, kept forever in 4.0 (V7 (g)). root_id cascades with its root item: the service refuses a
-- root delete while a run on it is open or paused, so only closed runs ever follow a root away (their run.ended
-- event rows survive; events carry no FK). scope_id and pause_decision_id carry NO foreign key on purpose: a scope
-- item may be deleted without touching the run, and the decisions table arrives later (an FK added afterwards would
-- need a table recreation). opened_by_kind is opaque TEXT with no CHECK so a later principal-kind vocabulary needs
-- no recreation. The status and reason CHECKs are the closed run-lifecycle vocabularies. Timestamps are the
-- canonical 23-character UTC text of V20, so text order is time order. budget, hints and marks are JSON text.
--
-- run_items: one row per (run, member item); an item delete drops its memberships and records nothing extra.
--
-- Additive only: two new tables and two indexes; no existing object is touched, no data is migrated.

CREATE TABLE runs (
    id                  BLOB    PRIMARY KEY NOT NULL,
    root_id             BLOB    NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    scope_id            BLOB,
    label               TEXT    CHECK (label IS NULL OR length(label) <= 64),
    title               TEXT    CHECK (title IS NULL OR length(title) <= 200),
    status              TEXT    NOT NULL CHECK (status IN ('open', 'paused', 'closed')),
    pause_reason        TEXT    CHECK (pause_reason IS NULL OR pause_reason IN ('budget', 'attempts', 'manual')),
    pause_decision_id   BLOB,
    terminal_reason     TEXT    CHECK (terminal_reason IS NULL OR terminal_reason IN ('completed', 'cancelled', 'failed', 'abandoned', 'budget_exhausted')),
    opened_by           TEXT,
    opened_by_kind      TEXT,
    opened_at           TEXT    NOT NULL CHECK (length(opened_at) = 23),
    closed_by           TEXT,
    closed_at           TEXT    CHECK (closed_at IS NULL OR length(closed_at) = 23),
    coordinator_seen_at TEXT    CHECK (coordinator_seen_at IS NULL OR length(coordinator_seen_at) = 23),
    stall_after_s       INTEGER NOT NULL CHECK (stall_after_s > 0),
    budget              TEXT,
    reported_tokens     INTEGER NOT NULL DEFAULT 0,
    reported_cost_micro INTEGER NOT NULL DEFAULT 0,
    observed_tokens     INTEGER NOT NULL DEFAULT 0,
    hints               TEXT,
    marks               TEXT,
    version             INTEGER NOT NULL DEFAULT 1
);

CREATE INDEX idx_runs_root_status ON runs(root_id, status);

CREATE TABLE run_items (
    run_id   BLOB NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    item_id  BLOB NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    added_at TEXT NOT NULL CHECK (length(added_at) = 23),
    PRIMARY KEY (run_id, item_id)
);

CREATE INDEX idx_run_items_item ON run_items(item_id);
