-- V19: Idempotency records -- durable, per-element replay store for keyed writes.
--
-- One row per keyed element call: (principal_id, operation, key) -> the request fingerprint and the
-- stored result. Written inside the element's own unit of work, so a record exists if and only if
-- the element's effects committed (or, for payload-validation failures, a follow-up unit stored the
-- rejection). Rows older than 24 h are dead weight and are pruned by IdempotencyPruner.
--
-- Pure CREATE TABLE: no existing table is touched, no data migration, no foreign key (a principal is
-- not a table, and a record must outlive the item it created).
--
-- created_at is the ONE canonical persisted timestamp form: yyyy-MM-dd HH:mm:ss.SSS in UTC, a fixed
-- 23 characters, millisecond truncation. Fixed width makes text order equal time order, so the TTL
-- lookup and the prune DELETE compare bound parameters against the index. The length CHECK is the
-- DB-side guard against any other shape.

CREATE TABLE idempotency_records (
    principal_id TEXT NOT NULL,
    operation    TEXT NOT NULL,
    key          TEXT NOT NULL,
    fingerprint  TEXT NOT NULL CHECK (length(fingerprint) = 64),
    result_json  TEXT NOT NULL,
    created_at   TEXT NOT NULL CHECK (length(created_at) = 23),
    PRIMARY KEY (principal_id, operation, key)
);

CREATE INDEX idx_idempotency_records_created_at ON idempotency_records(created_at);
