-- V10: Fix missing description data in projects table (BUGFIX for V9)
-- Bug: V9 migration initially forgot to include description column when rebuilding projects table
-- V6 and V7 added description (nullable) to all container types, but V9 omitted it for projects
--
-- NOTE: V9 was subsequently fixed to preserve description column.
-- This migration is now a no-op for fresh installations (V9 handles description correctly).
-- For production databases that ran buggy V9, the description column was already added by
-- the original version of this migration.
--
-- This migration now only ensures data recovery for any edge cases where description is NULL
-- but summary has data (idempotent operation, safe to run on any database state).

-- Migrate existing summary data to description IF description is NULL
-- This is idempotent and safe - only updates rows where description is missing
UPDATE projects SET description = summary
WHERE description IS NULL AND summary IS NOT NULL AND summary != '';

-- Note: The original ALTER TABLE ADD COLUMN has been removed because:
-- 1. Fresh installations: V9 (fixed) already creates description column
-- 2. Production databases: Original V10 already added description column
-- 3. This version is fully idempotent and backward compatible
