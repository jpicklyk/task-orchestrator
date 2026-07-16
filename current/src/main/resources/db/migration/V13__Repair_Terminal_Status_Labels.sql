-- Data-only repair for bug 100da214: a "start" trigger that resolved to TERMINAL (WORK->TERMINAL
-- on a no-review schema, or REVIEW->TERMINAL) stamped the work-phase label ("in-progress") instead
-- of the terminal label ("done"), because the pre-fix label lookup was keyed on the raw trigger
-- string rather than the resolved target role. This backfills existing terminal rows that were
-- mislabeled by that bug.
--
-- Scope is deliberately NARROW: it repairs ONLY the default work-phase label 'in-progress' left on a
-- terminal row. It does NOT blanket-rewrite every non-'done'/'cancelled' terminal label, because
-- status_labels is a supported per-root override (a project may legitimately configure a custom
-- terminal label such as 'finished' or 'shipped'), and those correctly-labeled rows must not be
-- clobbered. A deployment that also customized its start/work-phase label away from 'in-progress'
-- will not be auto-repaired here (it can repair manually) -- that is the safe trade-off versus
-- corrupting customized terminal labels.
--
-- Idempotent: re-running finds no matching rows the second time (the row is already 'done').
-- 'cancelled'-safe and NULL-tolerant by construction (neither equals 'in-progress').
-- No schema/table change -- DirectDatabaseSchemaManager is unaffected.
UPDATE work_items
SET status_label = 'done'
WHERE role = 'terminal'
  AND status_label = 'in-progress';
