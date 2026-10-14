-- V35: run seats, the claim assignment and the note seat (plan section 4.1, R22).
--
-- run_seats: one row per seat assignment, kept forever in 4.0 (V7 (g)). run_id cascades with its run; item_id cascades
-- with its item and is NULLABLE for exactly one kind of row: the run-scoped `coordinator` pseudo-seat that `open`
-- creates (every other row carries an item id; the service enforces it, not the DDL). The seat token is never stored:
-- token_hash is its lowercase hex SHA-256 (64 characters), unique, and every token-carrying call looks it up by hash.
-- status/outcome are the closed assignment vocabularies; an active row has no outcome and no ended_at, an ended row has
-- both. summary is at most 500 characters; links (at most 10 {kind, ref}) and hints are JSON text validated by the
-- service. Timestamps are the canonical 23-character UTC text of V20, so text order is time order. usage_tokens and
-- usage_cost_micro (micro-USD) keep the highest absolute usage the seat reported.
--
-- Two partial unique indexes: at most one ACTIVE assignment per (run, item, seat), and at most one ACTIVE coordinator
-- per run (SQLite treats NULL item ids as distinct in a unique index, so the first index alone would not bound the
-- coordinator rows).
--
-- work_items.claim_assignment_id: the seat assignment a claim is held under (NULL = a principal-level claim). No FK on
-- purpose: an assignment row disappears only with its run (that is, with the root and its items); a dangling id would
-- merely never match a holder, and an FK added later would need a table recreation.
--
-- notes.seat: the seat of the note's owner (NULL = a principal-level owner, every pre-4.0 note).
--
-- Additive only: one new table with four indexes and two nullable ADD COLUMNs with no default (schema-only, no row
-- rewrite); no existing object is touched, no data is migrated.

CREATE TABLE run_seats (
    id               BLOB    PRIMARY KEY NOT NULL,
    run_id           BLOB    NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    item_id          BLOB    REFERENCES work_items(id) ON DELETE CASCADE,
    seat             TEXT    NOT NULL CHECK (length(seat) BETWEEN 1 AND 64),
    attempt          INTEGER NOT NULL CHECK (attempt >= 1),
    principal_id     TEXT    NOT NULL,
    token_hash       TEXT    NOT NULL UNIQUE CHECK (length(token_hash) = 64),
    status           TEXT    NOT NULL CHECK (status IN ('active', 'ended')),
    outcome          TEXT    CHECK (outcome IS NULL OR outcome IN ('done', 'failed', 'abandoned', 'superseded', 'run_closed')),
    summary          TEXT    CHECK (summary IS NULL OR length(summary) <= 500),
    links            TEXT,
    hints            TEXT,
    assigned_at      TEXT    NOT NULL CHECK (length(assigned_at) = 23),
    ended_at         TEXT    CHECK (ended_at IS NULL OR length(ended_at) = 23),
    last_seen_at     TEXT    CHECK (last_seen_at IS NULL OR length(last_seen_at) = 23),
    usage_tokens     INTEGER NOT NULL DEFAULT 0 CHECK (usage_tokens >= 0),
    usage_cost_micro INTEGER NOT NULL DEFAULT 0 CHECK (usage_cost_micro >= 0),
    CHECK (
        (status = 'active' AND outcome IS NULL AND ended_at IS NULL)
        OR (status = 'ended' AND outcome IS NOT NULL AND ended_at IS NOT NULL)
    )
);

CREATE INDEX idx_run_seats_run_status ON run_seats(run_id, status);
CREATE INDEX idx_run_seats_item ON run_seats(item_id);
CREATE UNIQUE INDEX uq_run_seats_active ON run_seats(run_id, item_id, seat) WHERE status = 'active';
CREATE UNIQUE INDEX uq_run_seats_coordinator ON run_seats(run_id) WHERE seat = 'coordinator' AND status = 'active';

ALTER TABLE work_items ADD COLUMN claim_assignment_id BLOB;

ALTER TABLE notes ADD COLUMN seat TEXT;
