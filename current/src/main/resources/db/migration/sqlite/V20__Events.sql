-- V20: Events -- the append-only domain-event log (plan section 3.7).
--
-- One row per domain event, appended inside the unit of work that made the change, so a row exists
-- if and only if that change committed (rejections are appended by a short follow-up unit). The SSE
-- stream at GET /api/v1/events is a projection of this table: Last-Event-ID is seq.
--
-- Pure CREATE TABLE/INDEX plus one sqlite_sequence seed: no existing table is touched, no data
-- migration, no foreign key (a row must outlive the item it describes), no CHECK on type or
-- entity_kind (the typed catalog grows in later phases and widening a CHECK needs a recreation).
--
-- occurred_at is the canonical persisted timestamp form: yyyy-MM-dd HH:mm:ss.SSS in UTC, a fixed
-- 23 characters, so text order equals time order. root_id is never null: a root item uses its own id.
--
-- AUTOINCREMENT keeps seq from ever being reused, even after a future retention DELETE. The seed row
-- starts seq above 1e12, so every Last-Event-ID issued by the pre-V20 in-memory ring buffer (small
-- per-process counters) is distinguishable from a table seq and is answered with sync.lost.

CREATE TABLE events (
    seq            INTEGER PRIMARY KEY AUTOINCREMENT,
    id             BLOB NOT NULL UNIQUE,
    occurred_at    TEXT NOT NULL CHECK (length(occurred_at) = 23),
    root_id        BLOB NOT NULL,
    entity_kind    TEXT NOT NULL,
    entity_id      BLOB NOT NULL,
    type           TEXT NOT NULL,
    req_id         TEXT,
    principal_id   TEXT,
    principal_kind TEXT,
    proof_status   TEXT,
    host           TEXT,
    session_id     TEXT,
    run_id         BLOB,
    seat           TEXT,
    data           TEXT NOT NULL
);

CREATE INDEX idx_events_root_seq ON events(root_id, seq);
CREATE INDEX idx_events_entity_seq ON events(entity_id, seq);
CREATE INDEX idx_events_type_occurred ON events(type, occurred_at);
CREATE INDEX idx_events_req_id ON events(req_id);

INSERT INTO sqlite_sequence (name, seq) VALUES ('events', 1000000000000);
