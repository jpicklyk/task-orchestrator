-- Add unblock_at column to dependencies table for role-based dependency gating
-- NULL means "terminal" (backward compatible with existing behavior)
-- Valid values: queue, work, review, terminal, blocked
ALTER TABLE dependencies ADD COLUMN unblock_at TEXT DEFAULT NULL;
