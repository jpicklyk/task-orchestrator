-- V37: typed blocked reason (plan section 4.1, R26).
--
-- work_items.blocked_reason: why a BLOCKED item is blocked, one of awaiting_decision | dependency | resource | manual.
-- NULL outside the blocked role and on every pre-V37 row: a legacy BLOCKED row keeps NULL and is READ as `manual`
-- (WorkItem.effectiveBlockedReason); no row is rewritten.
--
-- work_items.blocked_ref: the decision request id (16-byte UUID BLOB like every id column) for `awaiting_decision`,
-- NULL otherwise. No FK on purpose (the V35 claim_assignment_id reasoning): a decision row disappears only with its item
-- or root, and an FK added later would need a work_items recreation (8 triggers, 2 FTS tables).
--
-- The cross-column rules (a reason only on role blocked; a ref with, and only with, awaiting_decision) are domain
-- invariants (WorkItem.violations), not DDL: ADD COLUMN cannot add a cross-column CHECK without a recreation.
--
-- Additive only: two nullable ADD COLUMNs with no default (schema-only, no row rewrite); no existing object is touched.

ALTER TABLE work_items ADD COLUMN blocked_reason TEXT
    CHECK (blocked_reason IS NULL OR blocked_reason IN ('awaiting_decision', 'dependency', 'resource', 'manual'));
ALTER TABLE work_items ADD COLUMN blocked_ref BLOB;