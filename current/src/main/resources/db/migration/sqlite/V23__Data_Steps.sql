-- V23: Data steps -- the ledger of applied one-shot upgrade steps (plan section 3.7).
--
-- One row per once data step that has run: the step name, when it was applied, how many rows it
-- affected and the binary version that applied it. The runner (DataStepRunner) records a row in the
-- same transaction as the step body, so a step is applied if and only if its row exists.
--
-- Pure CREATE TABLE: no existing table is touched, no data migration, no foreign key (a row outlives
-- everything) and no index (every access is a primary-key probe or a scan of a few rows). NOT NULL
-- on the TEXT primary key is required: SQLite otherwise allows NULL in a non-INTEGER primary key.
--
-- applied_at is the canonical persisted timestamp form: yyyy-MM-dd HH:mm:ss.SSS in UTC, a fixed 23
-- characters, so text order equals time order.

CREATE TABLE data_steps (
    name           TEXT    NOT NULL PRIMARY KEY CHECK (length(name) BETWEEN 1 AND 64),
    applied_at     TEXT    NOT NULL CHECK (length(applied_at) = 23),
    rows_affected  INTEGER NOT NULL CHECK (rows_affected >= 0),
    binary_version TEXT    NOT NULL
);
