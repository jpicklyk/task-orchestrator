-- Data-only repair for bug 100da214: a "start" trigger that resolved to TERMINAL (WORK->TERMINAL
-- on a no-review schema, or REVIEW->TERMINAL) stamped the work-phase label ("in-progress") instead
-- of the terminal label ("done"), because the pre-fix label lookup was keyed on the raw trigger
-- string rather than the resolved target role. This backfills existing terminal rows that were
-- mislabeled by that bug.
--
-- Idempotent: re-running finds no matching rows the second time (status_label is already 'done').
-- NULL-tolerant: rows with a NULL status_label are left untouched (not part of this bug's symptom).
-- Does not touch 'cancelled' rows (cancel already stamps its own correct label) or rows already
-- 'done'. No schema/table change -- DirectDatabaseSchemaManager is unaffected.
UPDATE work_items
SET status_label = 'done'
WHERE role = 'terminal'
  AND status_label IS NOT NULL
  AND status_label NOT IN ('done', 'cancelled');
