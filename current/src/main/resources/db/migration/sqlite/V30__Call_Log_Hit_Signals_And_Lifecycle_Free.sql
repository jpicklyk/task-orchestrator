-- V30: Two call_log telemetry columns.
--
-- hit_signals: for a `related` call, which signal(s) found each returned hit, so a later call in
-- the same session that targets that id (target_ids or target_versions) counts as follow-through
-- for those signals. JSON array in page order:
--   [{"id":"<canonical uuid>","kind":"item"|"document","signals":["edges","links","tags","text"]}]
-- where signals lists, in that fixed order, the signals whose rank map holds the hit. NULL for
-- every other call and for a related call whose page is empty. Never a title or any text.
--
-- lifecycle_free_rejections: how many rejections in the response were caused by a lifecycle-free
-- item (an invalid_transition with detail.fromRole "none", or a dependency BLOCKS endpoint
-- refusal). NULL when there were none; never 0.
--
-- Additive only: two nullable ADD COLUMNs, no table recreation, no backfill (existing rows read
-- NULL, which means "not captured"). No CHECK and no DEFAULT, matching V21's policy for count
-- columns (widening a CHECK later needs a table recreation). No index: the reports filter by
-- (tool, at), already covered by idx_call_log_tool_at.

ALTER TABLE call_log ADD COLUMN hit_signals TEXT;
ALTER TABLE call_log ADD COLUMN lifecycle_free_rejections INTEGER;
