-- V31: call_log.root_id plus the run indexes the W6 reports need.
--
-- root_id: the single root a call read (target-version reads) or wrote (events); NULL when the call
-- touched no root or more than one (request_shape.multiRoot then says so). BLOB, same 16-byte UUID
-- encoding as run_id and events.root_id. Nullable, no DEFAULT, no CHECK, no FK (a call_log row
-- outlives the items it names, V21 policy). Existing rows read NULL = "not captured"; no backfill.
--
-- Indexes: reports filter call_log by (root_id, at) and by run (run_id, at), and events by
-- (run_id, seq). The run indexes are partial (run_id IS NOT NULL): every row is NULL until the
-- run id is stamped, so they stay empty and cost nothing until then.
--
-- Additive only: one nullable ADD COLUMN (metadata-only) and three CREATE INDEX statements.

ALTER TABLE call_log ADD COLUMN root_id BLOB;

CREATE INDEX idx_call_log_root_at ON call_log(root_id, at);
CREATE INDEX idx_call_log_run_at ON call_log(run_id, at) WHERE run_id IS NOT NULL;
CREATE INDEX idx_events_run_seq ON events(run_id, seq) WHERE run_id IS NOT NULL;
